package vn.tamtd.bot.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.config.ExchangeMode;
import vn.tamtd.bot.exchange.ExchangeClient;
import vn.tamtd.bot.exchange.FilterCache;
import vn.tamtd.bot.exchange.SymbolFilter;
import vn.tamtd.bot.notify.NotifyEvent;
import vn.tamtd.bot.notify.Notifier;
import vn.tamtd.bot.storage.BotState;
import vn.tamtd.bot.storage.Position;
import vn.tamtd.bot.storage.StateStore;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Khôi phục Position cho các coin đang có trong ví Binance nhưng KHÔNG có trong
 * {@link BotState#positions} (VD user vừa xoá {@code ./data/state.json}).
 *
 * <p><b>Chiến lược "coi như mới mua"</b>: với mỗi asset đủ điều kiện, tạo Position
 * với {@code entryPrice = currentPrice} lúc start. Bot sẽ manage forward từ mốc đó -
 * TP, SL, trailing, top-up (nếu bật) hoạt động bình thường.
 *
 * <p>KHÔNG xâm phạm các luồng khác:
 * <ul>
 *   <li>Kill-switch unrealized PnL = 0 ngay sau rehydrate (không fire sai).</li>
 *   <li>Top-up cần {@code tpLevelsHit >= 1} nên không kích hoạt ngay cho coin rehydrate.</li>
 *   <li>Scanner/Watchlist vẫn skip coin đã có position.</li>
 *   <li>reserveFund không bị trừ (vì bot không thực sự mua - coin đã có sẵn).</li>
 * </ul>
 *
 * <p>Chỉ chạy ở SPOT (futures có cơ chế position tracking riêng qua positionRisk API).
 */
public final class RehydrateService {

    private static final Logger log = LoggerFactory.getLogger(RehydrateService.class);

    private final ConfigRegistry configRegistry;
    private final ExchangeClient exchangeClient;
    private final FilterCache filterCache;
    private final CapitalInitializer capitalInitializer;
    private final StateStore stateStore;
    private final Notifier notifier;

    public RehydrateService(ConfigRegistry configRegistry,
                            ExchangeClient exchangeClient,
                            FilterCache filterCache,
                            CapitalInitializer capitalInitializer,
                            StateStore stateStore,
                            Notifier notifier) {
        this.configRegistry = configRegistry;
        this.exchangeClient = exchangeClient;
        this.filterCache = filterCache;
        this.capitalInitializer = capitalInitializer;
        this.stateStore = stateStore;
        this.notifier = notifier;
    }

    /**
     * Thực thi rehydrate nếu config cho phép. Mutate {@code state.positions} và
     * persist qua {@link StateStore}. Không throw - mọi lỗi chỉ log + skip.
     *
     * @return số lượng Position đã rehydrate (0 nếu skip).
     */
    public int rehydrateIfNeeded(BotState state) {
        AppConfig config = configRegistry.current();
        AppConfig.Recovery rec = config.recovery();
        if (rec == null || !rec.rehydrateOnStartV()) {
            log.debug("[REHYDRATE] recovery.rehydrateOnStart=false - skip");
            return 0;
        }
        if (config.mode() != ExchangeMode.SPOT) {
            log.info("[REHYDRATE] Chỉ hỗ trợ SPOT (mode hiện tại: {}) - skip", config.mode());
            return 0;
        }
        if (rec.onlyWhenStateEmptyV() && !state.positions.isEmpty()) {
            log.info("[REHYDRATE] state.positions đã có {} entry - skip (onlyWhenStateEmpty=true)",
                    state.positions.size());
            return 0;
        }

        CapitalInitializer.AccountSnapshot snap;
        try {
            snap = capitalInitializer.fetchAccountSnapshot();
        } catch (Exception e) {
            log.warn("[REHYDRATE] Fetch account lỗi: {} - skip", e.getMessage());
            return 0;
        }

        double minValue = rec.minAssetValueUsdtV();
        List<Candidate> candidates = new ArrayList<>();
        for (CapitalInitializer.AssetHolding h : snap.holdings()) {
            if (h.source() != CapitalInitializer.AssetSource.PRICED) continue;  // skip USDT/STABLE/SKIPPED
            if (h.usdtValue() < minValue) {
                log.info("[REHYDRATE] Skip {} value={} USDT < minAssetValueUsdt={}",
                        h.asset(), String.format("%.4f", h.usdtValue()), minValue);
                continue;
            }
            String symbol = h.asset() + "USDT";
            if (state.positions.containsKey(symbol)) {
                log.info("[REHYDRATE] {} đã có trong state.positions - skip", symbol);
                continue;
            }
            SymbolFilter f = filterCache.get(symbol);
            if (f == null) {
                log.warn("[REHYDRATE] {} không có trên Binance SPOT (filter null) - skip", symbol);
                continue;
            }
            if (h.priceUsdt() == null || h.priceUsdt().signum() == 0) {
                log.warn("[REHYDRATE] {} không lấy được giá - skip", symbol);
                continue;
            }
            // Qty từ holdings tính cả locked (h.amount = free + locked). Trade forward
            // chỉ bán được phần free, nhưng TP/SL vẫn đúng trên toàn position. OrderExecutor
            // sẽ cap sell qty theo freeAsset (fix cũ) nên locked không gây fail order.
            candidates.add(new Candidate(symbol, BigDecimal.valueOf(h.amount()),
                    h.priceUsdt(), h.usdtValue()));
        }

        if (candidates.isEmpty()) {
            log.info("[REHYDRATE] Không có asset đủ điều kiện để rehydrate");
            return 0;
        }

        AppConfig.Exit exitDefaults = config.exit();
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("REHYDRATE: take over %d coin từ ví (entry = giá hiện tại):%n",
                candidates.size()));
        int created = 0;
        for (Candidate c : candidates) {
            try {
                AppConfig.Exit ex = config.exitFor(c.symbol);
                Position p = Position.spotEntry(c.symbol, c.qty, c.currentPrice,
                        "REHYDRATED",
                        ex.takeProfitPctV(), -ex.stopLossPctV());
                state.positions.put(c.symbol, p);
                created++;
                log.warn("[REHYDRATE] {} qty={} entryPrice={} (GIÁ HIỆN TẠI, không phải entry thật) value={} USDT",
                        c.symbol, c.qty.toPlainString(), c.currentPrice.toPlainString(),
                        String.format("%.4f", c.valueUsdt));
                summary.append(String.format("  • %s qty=%s @ %s ≈ %.4f USDT%n",
                        c.symbol, c.qty.toPlainString(),
                        c.currentPrice.toPlainString(), c.valueUsdt));
            } catch (Exception e) {
                log.error("[REHYDRATE] {} tạo position lỗi: {}", c.symbol, e.getMessage(), e);
            }
        }
        summary.append(String.format("TP=%.1f%% SL=%.1f%% tính TỪ GIÁ HIỆN TẠI. " +
                        "Nếu coin đang lãi/lỗ trước đó, bot không biết.",
                exitDefaults.takeProfitPctV(), exitDefaults.stopLossPctV()));
        stateStore.save(state);
        log.warn("[REHYDRATE] Persisted {} position. Summary:\n{}", created, summary);
        notifier.send(NotifyEvent.APP_START, summary.toString());
        return created;
    }

    private record Candidate(String symbol, BigDecimal qty,
                             BigDecimal currentPrice, double valueUsdt) {}
}
