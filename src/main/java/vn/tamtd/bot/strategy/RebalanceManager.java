package vn.tamtd.bot.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.indicator.TrendIndicators;
import vn.tamtd.bot.marketdata.BarSeriesCache;
import vn.tamtd.bot.storage.BotState;
import vn.tamtd.bot.storage.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bán vị thế đang lãi thấp / sideway để giải phóng USDT về reserveFund khi scanner cần.
 */
public final class RebalanceManager {

    private static final Logger log = LoggerFactory.getLogger(RebalanceManager.class);

    private final ConfigRegistry configRegistry;
    private final TrendIndicators trendIndicators;
    private final BarSeriesCache barSeriesCache;

    public RebalanceManager(ConfigRegistry configRegistry,
                            TrendIndicators trendIndicators,
                            BarSeriesCache barSeriesCache) {
        this.configRegistry = configRegistry;
        this.trendIndicators = trendIndicators;
        this.barSeriesCache = barSeriesCache;
    }

    public Optional<Decision.RebalanceSell> findCoinToFreeReserve(
            BotState state, Map<String, BigDecimal> currentPrices) {

        AppConfig config = configRegistry.current();
        double maxPnlEligible = config.risk().maxPnlPctEligibleForRebalance();
        String tfPrimary = config.timeframes().primary();

        List<Candidate> candidates = state.positions.values().stream()
                .map(p -> toCandidate(p, currentPrices, tfPrimary, maxPnlEligible))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparingDouble(Candidate::pnlPct))
                .toList();

        if (candidates.isEmpty()) return Optional.empty();
        Candidate best = candidates.get(0);
        String reason = String.format("Rebalance: bán %s (pnl=%.2f%%, trend không thuận)",
                best.position().symbol, best.pnlPct());
        log.info("[REBAL] Chọn {} (pnl={}%) để free reserveFund",
                best.position().symbol, String.format("%+.2f", best.pnlPct()));
        return Optional.of(new Decision.RebalanceSell(
                best.position().symbol, best.position().qty, reason));
    }

    private Optional<Candidate> toCandidate(Position p,
                                            Map<String, BigDecimal> prices,
                                            String tfPrimary,
                                            double maxPnlEligible) {
        BigDecimal price = prices.get(p.symbol);
        if (price == null) return Optional.empty();
        BigDecimal diff = p.isLong()
                ? price.subtract(p.entryPrice)
                : p.entryPrice.subtract(price);
        double pricePct = diff.divide(p.entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
        int lev = p.leverage == null ? 1 : p.leverage;
        double pnlPct = pricePct * lev;
        if (pnlPct >= maxPnlEligible) return Optional.empty();

        try {
            BarSeries s = barSeriesCache.get(p.symbol, tfPrimary);
            TrendIndicators.Trend trend = trendIndicators.classify(s);
            boolean favorable = p.isLong()
                    ? trend == TrendIndicators.Trend.UPTREND
                    : trend == TrendIndicators.Trend.DOWNTREND;
            if (favorable) return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(p, pnlPct));
    }

    private record Candidate(Position position, double pnlPct) {}
}
