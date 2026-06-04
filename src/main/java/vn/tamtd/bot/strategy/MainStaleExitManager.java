package vn.tamtd.bot.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.storage.BotState;
import vn.tamtd.bot.storage.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Time-stop cho vị thế MAIN (source != STANDBY).
 *
 * <p>Sau {@code mainMaxHoldHours}, nếu PnL% còn "lờ đờ" (< {@code mainStaleExitMaxPnlPct}
 * nhưng chưa lỗ tới ngưỡng SL), sinh {@link Decision.StaleExit} để đóng và giải phóng slot
 * + thu hồi vốn về reserveFund. Tránh case PAXG/U giữ nhiều ngày quanh 0% khoá cứng slot,
 * khiến bot bỏ lỡ tín hiệu mạnh.
 *
 * <p>TP/SL/trailing bình thường vẫn chạy song song (PositionManager handle) — lệnh đang
 * chạy tốt (PnL ≥ ngưỡng) hoặc đang lỗ sâu (≤ -SL) sẽ do TP/SL lo, không bị time-stop.
 */
public final class MainStaleExitManager {

    private static final Logger log = LoggerFactory.getLogger(MainStaleExitManager.class);

    private final ConfigRegistry configRegistry;

    public MainStaleExitManager(ConfigRegistry configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Kiểm tra từng vị thế MAIN. Trả về quyết định time-stop đầu tiên đủ điều kiện
     * (1 exit per tick để tránh đóng quá nhiều cùng lúc).
     */
    public Optional<Decision.StaleExit> evaluate(BotState state,
                                                 Map<String, BigDecimal> prices) {
        AppConfig config = configRegistry.current();
        int maxHoldHours = config.capital().mainMaxHoldHoursV();
        if (maxHoldHours <= 0) return Optional.empty();

        double maxPnlPct = config.capital().mainStaleExitMaxPnlPctV();

        for (Position p : state.positions.values()) {
            if ("STANDBY".equals(p.source)) continue;
            if (p.entryAt == null) continue;

            long ageHours = Duration.between(p.entryAt, Instant.now()).toHours();
            if (ageHours < maxHoldHours) continue;

            BigDecimal price = prices.get(p.symbol);
            if (price == null) continue;

            double pnlPct = computePnlPct(p, price);
            double slPct = config.exitFor(p.symbol).stopLossPctV();
            // Chỉ time-stop khi đang "lờ đờ": chưa đạt ngưỡng lãi và chưa lỗ tới SL.
            if (pnlPct >= maxPnlPct || pnlPct <= -slPct) {
                log.debug("[STALE-EXIT] {} age={}h pnlPct={}% ngoài vùng flat [{}, {}) - skip",
                        p.symbol, ageHours, String.format("%.2f", pnlPct),
                        String.format("%.2f", -slPct), String.format("%.2f", maxPnlPct));
                continue;
            }

            String reason = String.format(
                    "Time-stop: giữ %dh pnl=%+.2f%% < %.1f%% (lờ đờ) → giải phóng slot",
                    ageHours, pnlPct, maxPnlPct);
            log.info("[STALE-EXIT] {} age={}h pnl={}% → đóng để tái dùng vốn", p.symbol, ageHours,
                    String.format("%+.2f", pnlPct));
            return Optional.of(new Decision.StaleExit(p.symbol, p.qty, reason));
        }
        return Optional.empty();
    }

    private double computePnlPct(Position p, BigDecimal currentPrice) {
        BigDecimal diff = p.isLong()
                ? currentPrice.subtract(p.entryPrice)
                : p.entryPrice.subtract(currentPrice);
        return diff.divide(p.entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }
}
