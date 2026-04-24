package vn.tamtd.bot.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.indicator.TrendIndicators;
import vn.tamtd.bot.marketdata.BarSeriesCache;
import vn.tamtd.bot.scanner.ScanResult;
import vn.tamtd.bot.storage.BotState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Xác định coin nào nên ENTRY trong tick hiện tại - watchlist + scanner.
 * Với futures: entry = mở LONG (v1 long-only). Short sẽ add sau.
 * FundingRateGuard chặn trước nếu futures funding xấu.
 */
public final class EntryPlanner {

    private static final Logger log = LoggerFactory.getLogger(EntryPlanner.class);

    private final ConfigRegistry configRegistry;
    private final TrendIndicators trendIndicators;
    private final BarSeriesCache barSeriesCache;
    private final FundingRateGuard fundingGuard;

    public EntryPlanner(ConfigRegistry configRegistry,
                        TrendIndicators trendIndicators,
                        BarSeriesCache barSeriesCache,
                        FundingRateGuard fundingGuard) {
        this.configRegistry = configRegistry;
        this.trendIndicators = trendIndicators;
        this.barSeriesCache = barSeriesCache;
        this.fundingGuard = fundingGuard;
    }

    public List<Decision.EntryBuy> planWatchlistEntries(BotState state) {
        AppConfig config = configRegistry.current();
        List<Decision.EntryBuy> decisions = new ArrayList<>();
        List<String> symbols = config.watchlist().symbols();
        if (symbols.isEmpty()) return decisions;
        if (state.v0 <= 0) {
            log.warn("[ENTRY-W] v0={} chưa snapshot - skip watchlist", state.v0);
            return decisions;
        }

        double activeCapital = state.v0 * (1.0 - config.capital().reservePct());
        double allocPerCoin = activeCapital / symbols.size();
        log.info("[ENTRY-W] Plan watchlist: {} activeCapital={} allocPerCoin={}",
                symbols, fmt(activeCapital), fmt(allocPerCoin));
        if (allocPerCoin < config.capital().minTradeSizeUsdt()) {
            log.warn("[ENTRY-W] allocPerCoin {} < minTradeSize {} - skip tick",
                    fmt(allocPerCoin), config.capital().minTradeSizeUsdt());
            return decisions;
        }

        String tfPrimary = config.timeframes().primary();
        int rsiMin = config.signals().rsiEntryMin();
        int rsiMax = config.signals().rsiEntryMax();

        for (String symbol : symbols) {
            if (!config.isSymbolEnabled(symbol)) {
                log.debug("[ENTRY-W] {} disabled qua symbols.yml - skip", symbol);
                continue;
            }
            if (state.positions.containsKey(symbol)) continue;
            if (isInCooldown(state, symbol)) continue;
            if (state.positions.size() >= config.risk().maxConcurrentPositions()) {
                log.info("[ENTRY-W] {} đã đạt maxConcurrentPositions={} - skip",
                        symbol, config.risk().maxConcurrentPositions());
                continue;
            }
            if (!fundingGuard.allowsLong(symbol)) continue;
            try {
                BarSeries s = barSeriesCache.get(symbol, tfPrimary);
                TrendIndicators.Trend trend = trendIndicators.classify(s);
                double rsi = trendIndicators.rsi(s);
                if (trend != TrendIndicators.Trend.UPTREND) {
                    log.info("[ENTRY-W] {} trend={} - skip (cần UPTREND)",
                            symbol, trend);
                    continue;
                }
                if (rsi < rsiMin || rsi > rsiMax) {
                    log.info("[ENTRY-W] {} RSI={} ngoài [{},{}] - skip",
                            symbol, String.format("%.1f", rsi), rsiMin, rsiMax);
                    continue;
                }

                BigDecimal alloc = BigDecimal.valueOf(allocPerCoin)
                        .setScale(8, RoundingMode.DOWN);
                String reason = String.format("Watchlist entry: trend=UP, RSI=%.1f ∈ [%d,%d]",
                        rsi, rsiMin, rsiMax);
                log.info("[ENTRY-W] {} PASS → plan LONG {} USDT",
                        symbol, alloc.toPlainString());
                decisions.add(new Decision.EntryBuy(symbol, alloc, "WATCHLIST", reason));
            } catch (Exception e) {
                log.warn("[ENTRY-W] {} check lỗi: {}", symbol, e.getMessage());
            }
        }
        return decisions;
    }

    public List<Decision.EntryBuy> planScannerEntries(BotState state, List<ScanResult> scanResults) {
        AppConfig config = configRegistry.current();
        List<Decision.EntryBuy> decisions = new ArrayList<>();
        if (!config.scanner().enabled()) return decisions;

        int maxConc = config.risk().maxConcurrentPositions();
        boolean watchlistEmpty = config.watchlist().symbols().isEmpty();
        // Scanner-only mode (watchlist rỗng): chia đều v0 cho maxConcurrent slot,
        // không dùng reserveAllocPerOpportunityUsdt (vốn chỉ dành cho scanner "cơ hội vàng"
        // khi watchlist đã chiếm activeCapital).
        // Buffer 2% = phí taker 0.1%/lệnh + float precision (cumQuote lệch quoteOrderQty)
        // → tránh Binance reject -2010 "insufficient balance" ở lệnh cuối.
        BigDecimal allocPerOp = watchlistEmpty && state.v0 > 0
                ? BigDecimal.valueOf(state.v0 * 0.98 / maxConc).setScale(8, RoundingMode.DOWN)
                : BigDecimal.valueOf(config.capital().reserveAllocPerOpportunityUsdt());
        BigDecimal minSize = BigDecimal.valueOf(config.capital().minTradeSizeUsdt());
        BigDecimal reserve = BigDecimal.valueOf(state.reserveFund);
        log.info("[ENTRY-S] Scanner reserve={} allocPerOp={} minSize={} mode={}",
                reserve.toPlainString(), allocPerOp.toPlainString(), minSize.toPlainString(),
                watchlistEmpty ? "SCANNER_ONLY" : "MIXED");

        for (ScanResult r : scanResults) {
            if (r.signal() == ScanResult.Signal.NONE) continue;
            if (!config.isSymbolEnabled(r.symbol())) continue;
            if (state.positions.containsKey(r.symbol())) continue;
            if (isInCooldown(state, r.symbol())) continue;
            if (state.positions.size() + decisions.size() >= maxConc) break;
            if (!fundingGuard.allowsLong(r.symbol())) continue;

            if (reserve.compareTo(minSize) < 0) {
                log.info("[ENTRY-S] reserve hết < minSize - break");
                break;
            }
            BigDecimal alloc = allocPerOp.min(reserve);
            String reason = String.format("Scanner signal=%s score=%.2f qv=%,.0f",
                    r.signal(), r.score(), r.quoteVolume24h());
            log.info("[ENTRY-S] {} PASS signal={} → plan LONG {} USDT",
                    r.symbol(), r.signal(), alloc.toPlainString());
            decisions.add(new Decision.EntryBuy(r.symbol(), alloc, "SCANNER", reason));
            reserve = reserve.subtract(alloc);
        }
        return decisions;
    }

    private boolean isInCooldown(BotState state, String symbol) {
        Instant until = state.cooldowns.get(symbol);
        return until != null && Instant.now().isBefore(until);
    }

    private static String fmt(double d) { return String.format("%.2f", d); }
}
