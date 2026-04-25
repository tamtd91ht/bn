package vn.tamtd.bot.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.executor.OrderExecutor;
import vn.tamtd.bot.storage.BotState;
import vn.tamtd.bot.storage.JsonlWriter;
import vn.tamtd.bot.storage.StateStore;
import vn.tamtd.bot.strategy.CapitalInitializer;
import vn.tamtd.bot.strategy.Decision;
import vn.tamtd.bot.strategy.StrategyCoordinator;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 1 tick bot. Tick dùng config snapshot chụp lúc bắt đầu (qua {@link ConfigRegistry#current()});
 * nếu config reload giữa tick, tick hiện tại không bị ảnh hưởng - tick sau sẽ dùng snapshot mới.
 *
 * <p>Có 2 mode:
 * <ul>
 *   <li>{@link Mode#FULL}: tick chính (15p) - capital snapshot + positions + scanner + entries.</li>
 *   <li>{@link Mode#POSITIONS_ONLY}: fast-tick (60s) - chỉ check TP/SL/kill-switch.</li>
 * </ul>
 *
 * <p>Cả 2 mode đều {@code synchronized(state)} → 2 thread không bao giờ chạy đồng thời.
 */
public final class TickLoop {

    public enum Mode { FULL, POSITIONS_ONLY }

    private static final Logger log = LoggerFactory.getLogger(TickLoop.class);

    private final ConfigRegistry configRegistry;
    private final CapitalInitializer capitalInitializer;
    private final StrategyCoordinator coordinator;
    private final OrderExecutor orderExecutor;
    private final JsonlWriter jsonlWriter;
    private final StateStore stateStore;
    private final BotState state;
    private final Mode mode;

    private long tickCounter = 0;

    public TickLoop(ConfigRegistry configRegistry,
                    CapitalInitializer capitalInitializer,
                    StrategyCoordinator coordinator,
                    OrderExecutor orderExecutor,
                    JsonlWriter jsonlWriter,
                    StateStore stateStore,
                    BotState state) {
        this(configRegistry, capitalInitializer, coordinator, orderExecutor,
                jsonlWriter, stateStore, state, Mode.FULL);
    }

    public TickLoop(ConfigRegistry configRegistry,
                    CapitalInitializer capitalInitializer,
                    StrategyCoordinator coordinator,
                    OrderExecutor orderExecutor,
                    JsonlWriter jsonlWriter,
                    StateStore stateStore,
                    BotState state,
                    Mode mode) {
        this.configRegistry = configRegistry;
        this.capitalInitializer = capitalInitializer;
        this.coordinator = coordinator;
        this.orderExecutor = orderExecutor;
        this.jsonlWriter = jsonlWriter;
        this.stateStore = stateStore;
        this.state = state;
        this.mode = mode;
    }

    public void safeTick() {
        tickCounter++;
        try {
            // Lock state cho cả tick để tránh race với loop kia
            synchronized (state) { tick(); }
        } catch (Exception e) {
            log.error("TickLoop[{}] #{} lỗi", mode, tickCounter, e);
        }
    }

    private void tick() {
        Instant start = Instant.now();
        long tickTs = start.toEpochMilli();
        String tag = mode == Mode.FULL ? "Tick" : "FastTick";
        log.info(">>> {} #{} start {}", tag, tickCounter, start);

        if (mode == Mode.FULL) {
            capitalInitializer.snapshotIfNeeded(state);
        }
        log.info("  v0={} reserveFund={} positions={} paused={}",
                String.format("%.2f", state.v0),
                String.format("%.2f", state.reserveFund),
                state.positions.keySet(),
                state.paused);

        List<Decision> decisions = mode == Mode.FULL
                ? coordinator.tick(state)
                : coordinator.tickPositions(state);

        // Equity log chỉ ở FULL tick để tránh spam jsonl
        if (mode == Mode.FULL) {
            jsonlWriter.append(JsonlWriter.Bucket.EQUITY, Map.of(
                    "ts", start.toString(),
                    "tick", tickCounter,
                    "mode", configRegistry.current().mode().name(),
                    "v0", state.v0,
                    "reserveFund", state.reserveFund,
                    "openPositions", state.positions.keySet()
            ));
        }

        if (decisions.isEmpty()) {
            log.info("  Idle {}", tag.toLowerCase());
        } else {
            log.info("  [{}] decisions", decisions.size());
            for (Decision d : decisions) {
                jsonlWriter.append(JsonlWriter.Bucket.SIGNALS, Map.of(
                        "ts", Instant.now().toString(),
                        "tick", tickCounter,
                        "mode", mode.name(),
                        "type", d.getClass().getSimpleName(),
                        "symbol", d.symbol(),
                        "reason", d.reason()
                ));
                orderExecutor.execute(d, state, tickTs);
            }
        }

        stateStore.save(state);
        log.info("<<< {} #{} done ({}ms)", tag, tickCounter,
                Duration.between(start, Instant.now()).toMillis());
    }
}
