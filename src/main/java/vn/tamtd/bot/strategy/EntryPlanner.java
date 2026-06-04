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
        BigDecimal minSize = BigDecimal.valueOf(config.capital().minTradeSizeUsdt());

        // Size lệnh dựa trên FREE USDT thật trên Binance (có cache trong AccountCache)
        // thay vì state.v0 cache trong state file - user có thể nạp/rút USDT thủ công
        // hoặc lệnh trước đã ăn fee → v0 cache không phản ánh đúng số tiền sẵn sàng đặt.
        //
        // Scanner-only mode: chia deployable USDT cho số main slot còn trống.
        // deployable = freeUsdt - standbyFund (không ăn vào phần standby carve-out).
        // Buffer 2% = phí taker 0.1%/lệnh + float precision → tránh -2010 "insufficient balance".
        // Mixed mode: vẫn dùng reserveAllocPerOpportunityUsdt (cơ hội scanner chỉ rút trần cố định).
        BigDecimal allocPerOp;
        BigDecimal runningFree;
        if (watchlistEmpty) {
            BigDecimal freeUsdt = accountCache.freeUsdt();
            // Trừ standbyFund ra để main slots không ăn vào phần dự phòng
            BigDecimal standby = BigDecimal.valueOf(state.standbyFund);
            BigDecimal deployable = freeUsdt.subtract(standby).max(BigDecimal.ZERO);
            int mainOpen = countMainPositions(state);
            int remainingSlots = Math.max(1, maxConc - mainOpen);
            allocPerOp = deployable.multiply(BigDecimal.valueOf(0.98))
                    .divide(BigDecimal.valueOf(remainingSlots), 8, RoundingMode.DOWN);
            // Kẹp trần mỗi lệnh để giữ entry nhỏ, dành deployable cho tín hiệu các tick sau.
            double cap = config.capital().maxAllocPerTradeUsdtV();
            if (cap > 0) allocPerOp = allocPerOp.min(BigDecimal.valueOf(cap));
            runningFree = deployable;
            log.info("[ENTRY-S] Scanner freeUsdt={} standby={} deployable={} allocPerOp={} minSize={} mode=SCANNER_ONLY",
                    freeUsdt.toPlainString(), standby.toPlainString(),
                    deployable.toPlainString(), allocPerOp.toPlainString(), minSize.toPlainString());
        } else {
            allocPerOp = BigDecimal.valueOf(config.capital().reserveAllocPerOpportunityUsdt());
            runningFree = accountCache.freeUsdt();
            log.info("[ENTRY-S] Scanner freeUsdt={} allocPerOp={} minSize={} mode=MIXED",
                    runningFree.toPlainString(), allocPerOp.toPlainString(), minSize.toPlainString());
        }

        for (ScanResult r : scanResults) {
            if (r.signal() == ScanResult.Signal.NONE) continue;
            if (!config.isSymbolEnabled(r.symbol())) continue;
            if (state.positions.containsKey(r.symbol())) continue;
            if (isInCooldown(state, r.symbol())) continue;
            // Chỉ đếm main positions (không tính STANDBY) cho giới hạn maxConc
            if (countMainPositions(state) + decisions.size() >= maxConc) break;
            if (!fundingGuard.allowsLong(r.symbol())) continue;

            if (runningFree.compareTo(minSize) < 0) {
                log.info("[ENTRY-S] deployable còn {} < minSize {} - break",
                        runningFree.toPlainString(), minSize.toPlainString());
                break;
            }
            BigDecimal alloc = allocPerOp.min(runningFree).setScale(8, RoundingMode.DOWN);
            String reason = String.format("Scanner signal=%s score=%.2f qv=%,.0f",
                    r.signal(), r.score(), r.quoteVolume24h());
            log.info("[ENTRY-S] {} PASS signal={} → plan LONG {} USDT",
                    r.symbol(), r.signal(), alloc.toPlainString());
            decisions.add(new Decision.EntryBuy(r.symbol(), alloc, "SCANNER", reason));
            runningFree = runningFree.subtract(alloc);
        }
        return decisions;
    }

    /**
     * Tìm entry từ standby fund khi main slots đã đầy.
     * Chọn signal tốt nhất (score cao nhất) chưa có trong positions/cooldown/alreadyPlanned.
     *
     * @param alreadyPlanned các decision đã plan trong tick này (wl + scanner)
     */
    public List<Decision.EntryBuy> planStandbyEntry(BotState state,
                                                     List<ScanResult> scanResults,
                                                     List<Decision.EntryBuy> alreadyPlanned) {
        AppConfig config = configRegistry.current();
        if (!config.capital().standbyEnabled()) return List.of();
        if (!config.scanner().enabled()) return List.of();

        int maxConc = config.risk().maxConcurrentPositions();
        int mainOpen = countMainPositions(state);
        int mainPlanned = (int) alreadyPlanned.stream()
                .filter(d -> !"STANDBY".equals(d.source())).count();
        // Chỉ deploy standby khi main slots đã đầy (kể cả planned trong tick này)
        if (mainOpen + mainPlanned < maxConc) return List.of();

        int standbyOpen = countStandbyPositions(state);
        int standbyPlanned = (int) alreadyPlanned.stream()
                .filter(d -> "STANDBY".equals(d.source())).count();
        if (standbyOpen + standbyPlanned >= config.capital().standbyMaxPositionsV()) return List.of();

        BigDecimal minSize = BigDecimal.valueOf(config.capital().minTradeSizeUsdt());
        if (state.standbyFund < config.capital().minTradeSizeUsdt()) {
            log.debug("[ENTRY-STANDBY] standbyFund={} < minSize={} - skip",
                    String.format("%.2f", state.standbyFund), minSize.toPlainString());
            return List.of();
        }

        double minScore = config.capital().standbyMinScoreV();
        // Tập symbol đã có trong positions hoặc planned
        java.util.Set<String> occupied = new java.util.HashSet<>(state.positions.keySet());
        for (Decision.EntryBuy d : alreadyPlanned) occupied.add(d.symbol());

        // Tìm signal tốt nhất (score cao nhất) đủ điều kiện
        ScanResult best = null;
        for (ScanResult r : scanResults) {
            if (r.signal() == ScanResult.Signal.NONE) continue;
            if (r.score() < minScore) continue;
            if (!config.isSymbolEnabled(r.symbol())) continue;
            if (occupied.contains(r.symbol())) continue;
            if (isInCooldown(state, r.symbol())) continue;
            if (!fundingGuard.allowsLong(r.symbol())) continue;
            if (best == null || r.score() > best.score()) best = r;
        }

        if (best == null) {
            log.debug("[ENTRY-STANDBY] Không có signal đủ score >= {} sau khi lọc", minScore);
            return List.of();
        }

        BigDecimal freeUsdt = accountCache.freeUsdt();
        BigDecimal standbyAvail = freeUsdt.min(BigDecimal.valueOf(state.standbyFund));
        if (standbyAvail.compareTo(minSize) < 0) {
            log.warn("[ENTRY-STANDBY] standbyAvail={} < minSize={} (freeUsdt={})",
                    standbyAvail.toPlainString(), minSize.toPlainString(), freeUsdt.toPlainString());
            return List.of();
        }

        BigDecimal alloc = standbyAvail.multiply(BigDecimal.valueOf(0.98)).setScale(8, RoundingMode.DOWN);
        String reason = String.format("Standby entry: signal=%s score=%.2f qv=%,.0f (standbyFund=%.2f USDT)",
                best.signal(), best.score(), best.quoteVolume24h(), state.standbyFund);
        log.info("[ENTRY-STANDBY] {} PASS signal={} score={} → plan LONG {} USDT (standby)",
                best.symbol(), best.signal(), String.format("%.2f", best.score()), alloc.toPlainString());
        return List.of(new Decision.EntryBuy(best.symbol(), alloc, "STANDBY", reason));
    }

    /** Đếm main positions (source != STANDBY). */
    private static int countMainPositions(BotState state) {
        return (int) state.positions.values().stream()
                .filter(p -> !"STANDBY".equals(p.source)).count();
    }

    /** Đếm standby positions. */
    private static int countStandbyPositions(BotState state) {
        return (int) state.positions.values().stream()
                .filter(p -> "STANDBY".equals(p.source)).count();
    }

    private boolean isInCooldown(BotState state, String symbol) {
        Instant until = state.cooldowns.get(symbol);
        return until != null && Instant.now().isBefore(until);
    }

    private static String fmt(double d) { return String.format("%.2f", d); }
}
