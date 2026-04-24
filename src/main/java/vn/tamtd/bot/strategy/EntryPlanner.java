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
import vn.tamtd.bot.storage.Position;

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
    private final AccountCache accountCache;

    public EntryPlanner(ConfigRegistry configRegistry,
                        TrendIndicators trendIndicators,
                        BarSeriesCache barSeriesCache,
                        FundingRateGuard fundingGuard,
                        AccountCache accountCache) {
        this.configRegistry = configRegistry;
        this.trendIndicators = trendIndicators;
        this.barSeriesCache = barSeriesCache;
        this.fundingGuard = fundingGuard;
        this.accountCache = accountCache;
    }

    public List<Decision.EntryBuy> planWatchlistEntries(BotState state) {
        AppConfig config = configRegistry.current();
        List<Decision.EntryBuy> decisions = new ArrayList<>();
        List<String> symbols = config.watchlist().symbols();
        if (symbols.isEmpty()) return decisions;

        // Size lệnh lấy theo FREE USDT thật (tôn trọng nạp/rút thủ công) thay vì v0 cache.
        double freeUsdt = accountCache.freeUsdt().doubleValue();
        double activeCapital = freeUsdt * (1.0 - config.capital().reservePct());
        double allocPerCoin = activeCapital / symbols.size();
        log.info("[ENTRY-W] Plan watchlist: {} freeUsdt={} activeCapital={} allocPerCoin={}",
                symbols, fmt(freeUsdt), fmt(activeCapital), fmt(allocPerCoin));
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
            Position held = state.positions.get(symbol);
            boolean isTopUp = held != null;
            if (isTopUp && !canTopUp(held, config)) continue;
            if (isInCooldown(state, symbol)) continue;
            // Top-up không chiếm thêm slot (đã count trong state.positions rồi)
            if (!isTopUp && state.positions.size() >= config.risk().maxConcurrentPositions()) {
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
                if (isTopUp) {
                    alloc = capTopUpAlloc(held, alloc);
                    if (alloc.compareTo(BigDecimal.valueOf(config.capital().minTradeSizeUsdt())) < 0) {
                        log.info("[ENTRY-W:TOPUP] {} missing room < minTradeSize - skip", symbol);
                        continue;
                    }
                }
                String source = isTopUp ? "WATCHLIST_TOPUP" : "WATCHLIST";
                String reason = String.format("%s: trend=UP, RSI=%.1f ∈ [%d,%d]%s",
                        isTopUp ? "Watchlist top-up" : "Watchlist entry",
                        rsi, rsiMin, rsiMax,
                        isTopUp ? String.format(" (topUp #%d, shrink %.0f%%)",
                                held.topUpCount + 1, (1.0 - shrinkRatio(held)) * 100) : "");
                log.info("[ENTRY-W] {} PASS → plan {} {} USDT",
                        symbol, isTopUp ? "TOP-UP" : "LONG", alloc.toPlainString());
                decisions.add(new Decision.EntryBuy(symbol, alloc, source, reason));
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
        BigDecimal minSize = BigDecimal.valueOf(config.capital().minTradeSizeUsdt());

        // Size lệnh dựa trên FREE USDT thật trên Binance (có cache trong AccountCache)
        // thay vì state.v0 cache trong state file - user có thể nạp/rút USDT thủ công
        // hoặc lệnh trước đã ăn fee → v0 cache không phản ánh đúng số tiền sẵn sàng đặt.
        //
        // Scanner-only mode: chia free USDT cho số slot còn trống (maxConc − positions mở).
        // Buffer 2% = phí taker 0.1%/lệnh + float precision → tránh -2010 "insufficient balance".
        // Mixed mode: vẫn dùng reserveAllocPerOpportunityUsdt (cơ hội scanner chỉ rút trần cố định).
        BigDecimal allocPerOp;
        BigDecimal runningFree;
        if (watchlistEmpty) {
            BigDecimal freeUsdt = accountCache.freeUsdt();
            int remainingSlots = Math.max(1, maxConc - state.positions.size());
            allocPerOp = freeUsdt.multiply(BigDecimal.valueOf(0.98))
                    .divide(BigDecimal.valueOf(remainingSlots), 8, RoundingMode.DOWN);
            runningFree = freeUsdt;
        } else {
            allocPerOp = BigDecimal.valueOf(config.capital().reserveAllocPerOpportunityUsdt());
            runningFree = accountCache.freeUsdt();
        }
        log.info("[ENTRY-S] Scanner freeUsdt={} allocPerOp={} minSize={} mode={}",
                runningFree.toPlainString(), allocPerOp.toPlainString(), minSize.toPlainString(),
                watchlistEmpty ? "SCANNER_ONLY" : "MIXED");

        int newSymbolsSoFar = 0;
        for (ScanResult r : scanResults) {
            if (r.signal() == ScanResult.Signal.NONE) continue;
            if (!config.isSymbolEnabled(r.symbol())) continue;
            Position held = state.positions.get(r.symbol());
            boolean isTopUp = held != null;
            if (isTopUp && !canTopUp(held, config)) continue;
            if (isInCooldown(state, r.symbol())) continue;
            // Top-up không chiếm slot mới; chỉ đếm new symbols vào quota maxConc
            if (!isTopUp && state.positions.size() + newSymbolsSoFar >= maxConc) break;
            if (!fundingGuard.allowsLong(r.symbol())) continue;

            if (runningFree.compareTo(minSize) < 0) {
                log.info("[ENTRY-S] freeUsdt còn {} < minSize {} - break",
                        runningFree.toPlainString(), minSize.toPlainString());
                break;
            }
            BigDecimal alloc = allocPerOp.min(runningFree).setScale(8, RoundingMode.DOWN);
            if (isTopUp) {
                alloc = capTopUpAlloc(held, alloc);
                if (alloc.compareTo(minSize) < 0) {
                    log.info("[ENTRY-S:TOPUP] {} missing room < minSize - skip",
                            r.symbol());
                    continue;
                }
            }
            String source = isTopUp ? "SCANNER_TOPUP" : "SCANNER";
            String reason = String.format("Scanner %s signal=%s score=%.2f qv=%,.0f%s",
                    isTopUp ? "top-up" : "signal",
                    r.signal(), r.score(), r.quoteVolume24h(),
                    isTopUp ? String.format(" (topUp #%d, shrink %.0f%%)",
                            held.topUpCount + 1, (1.0 - shrinkRatio(held)) * 100) : "");
            log.info("[ENTRY-S] {} PASS signal={} → plan {} {} USDT",
                    r.symbol(), r.signal(), isTopUp ? "TOP-UP" : "LONG", alloc.toPlainString());
            decisions.add(new Decision.EntryBuy(r.symbol(), alloc, source, reason));
            runningFree = runningFree.subtract(alloc);
            if (!isTopUp) newSymbolsSoFar++;
        }
        return decisions;
    }

    /** Điều kiện cho phép top-up: flag bật + position đã partial TP đủ sâu + chưa hit max. */
    private boolean canTopUp(Position held, AppConfig config) {
        AppConfig.Capital cap = config.capital();
        if (!cap.allowTopUpAfterPartialV()) return false;
        if (held.tpLevelsHit < 1) return false;
        if (held.topUpCount >= cap.topUpMaxCountV()) return false;
        if (held.originalQty == null || held.originalQty.signum() == 0) return false;
        return shrinkRatio(held) < cap.topUpMinShrinkRatioV();
    }

    /** qty / originalQty - càng nhỏ = position càng co lại sau partial TP. */
    private double shrinkRatio(Position held) {
        if (held.originalQty == null || held.originalQty.signum() == 0) return 1.0;
        return held.qty.doubleValue() / held.originalQty.doubleValue();
    }

    /** Trần top-up alloc = "USDT còn thiếu để khôi phục size gốc" (tránh pyramid quá mức). */
    private BigDecimal capTopUpAlloc(Position held, BigDecimal allocPerOp) {
        if (held.originalQty == null) return allocPerOp;
        BigDecimal missing = held.originalQty.subtract(held.qty).multiply(held.entryPrice);
        if (missing.signum() <= 0) return BigDecimal.ZERO;
        return allocPerOp.min(missing).setScale(8, RoundingMode.DOWN);
    }

    private boolean isInCooldown(BotState state, String symbol) {
        Instant until = state.cooldowns.get(symbol);
        return until != null && Instant.now().isBefore(until);
    }

    private static String fmt(double d) { return String.format("%.2f", d); }
}
