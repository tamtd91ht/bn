package vn.tamtd.bot.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.exchange.FilterCache;
import vn.tamtd.bot.exchange.SymbolFilter;
import vn.tamtd.bot.indicator.TrendIndicators;
import vn.tamtd.bot.indicator.TrendIndicators.Trend;
import vn.tamtd.bot.storage.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Logic quản lý vị thế đang mở. Same as spot bản cũ + aware về futures LONG/SHORT.
 *
 * <p>PnL:
 * <ul>
 *   <li>LONG (spot/futures): (cur - entry)/entry × 100 × leverage</li>
 *   <li>SHORT (futures): (entry - cur)/entry × 100 × leverage</li>
 * </ul>
 * Leverage amplify % lợi nhuận trên margin - ngưỡng TP/SL trong config là % margin, không phải % giá.
 */
public final class PositionManager {

    private static final Logger log = LoggerFactory.getLogger(PositionManager.class);

    private final ConfigRegistry configRegistry;
    private final TrendIndicators trendIndicators;
    private final FilterCache filterCache;

    public PositionManager(ConfigRegistry configRegistry, TrendIndicators trendIndicators,
                           FilterCache filterCache) {
        this.configRegistry = configRegistry;
        this.trendIndicators = trendIndicators;
        this.filterCache = filterCache;
    }

    public Optional<Decision> evaluate(Position position, BarSeries series, BigDecimal currentPrice) {
        AppConfig cfg = configRegistry.current();
        AppConfig.Exit exit = cfg.exitFor(position.symbol);
        double pnlPct = computePnlPct(position, currentPrice);

        // Reset dropCheckCount nếu đã hồi qua recoveryResetPct
        if (pnlPct > exit.recoveryResetPctV() && position.dropCheckCount > 0) {
            log.info("[POS] {} pnl={}% hồi qua ngưỡng recovery, reset dropCheckCount từ {} về 0",
                    position.symbol, fmt(pnlPct), position.dropCheckCount);
            position.dropCheckCount = 0;
        }

        if (pnlPct >= position.currentTpPct) {
            log.info("[POS:TP] {} pnl={}% ≥ tpPct={}% → check trend để quyết TP partial/full",
                    position.symbol, fmt(pnlPct), fmt(position.currentTpPct));
            return evaluateTakeProfit(position, series, currentPrice, pnlPct, exit);
        }

        if (pnlPct <= -exit.stopLossPctV()) {
            // Grace period: bỏ qua SL trong N giây sau entry để tránh fast-tick bán vội
            // do giá slip lúc fill (MARKET BUY có thể fill cao hơn ticker vài tick).
            int graceSec = cfg.scheduling().entryGracePeriodSecondsV();
            if (graceSec > 0 && position.entryAt != null) {
                long sinceEntry = Duration.between(position.entryAt, Instant.now()).getSeconds();
                if (sinceEntry < graceSec) {
                    log.info("[POS:SL_GRACE] {} pnl={}% ≤ -slPct={}% nhưng mới entry {}s/{} - skip SL",
                            position.symbol, fmt(pnlPct), fmt(exit.stopLossPctV()),
                            sinceEntry, graceSec);
                    return Optional.empty();
                }
            }
            log.info("[POS:SL] {} pnl={}% ≤ -slPct={}% → check dropCheckCount/trend",
                    position.symbol, fmt(pnlPct), fmt(exit.stopLossPctV()));
            return evaluateStopLoss(position, series, pnlPct, exit);
        }

        log.debug("[POS] {} pnl={}% trong range [{},{}] → HOLD",
                position.symbol, fmt(pnlPct),
                fmt(-exit.stopLossPctV()), fmt(position.currentTpPct));
        return Optional.empty();
    }

    private Optional<Decision> evaluateTakeProfit(Position position, BarSeries series,
                                                  BigDecimal currentPrice,
                                                  double pnlPct, AppConfig.Exit exit) {
        Trend trend = trendIndicators.classify(series);
        // Với LONG: trend UP là "tăng tiếp". Với SHORT: trend DOWN mới là "tăng tiếp có lợi".
        boolean trendFavorable = position.isLong()
                ? trend == Trend.UPTREND
                : trend == Trend.DOWNTREND;

        if (trendFavorable) {
            double ratio = exit.partialTpRatioV();
            BigDecimal sellQty = position.qty.multiply(BigDecimal.valueOf(ratio))
                    .setScale(8, RoundingMode.DOWN);
            // Binance reject với -1013 "Filter failure: NOTIONAL" nếu sellQty × price < minNotional.
            // Khi coin giá nhỏ + partial 50% làm lệnh dưới 5 USDT → TP partial không bao giờ fill được,
            // position kẹt đến khi SL đánh xuống. Fallback sang TP full thay vì retry vô nghĩa.
            SymbolFilter filter = filterCache == null ? null : filterCache.get(position.symbol);
            if (filter != null) {
                BigDecimal sellNotional = sellQty.multiply(currentPrice);
                BigDecimal minNotional = filter.minNotional();
                if (minNotional != null && minNotional.signum() > 0
                        && sellNotional.compareTo(minNotional) < 0) {
                    String reason = String.format(
                            "TP full ở pnl=%.2f%% (partial %.0f%% = %s USDT < minNotional %s → chốt toàn bộ)",
                            pnlPct, ratio * 100,
                            sellNotional.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            minNotional.toPlainString());
                    log.info("[POS:TP_FULL_NOTIONAL] {} partial {} × {} = {} < minNotional {} → chốt toàn bộ qty={}",
                            position.symbol, sellQty.toPlainString(),
                            currentPrice.toPlainString(),
                            sellNotional.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                            minNotional.toPlainString(),
                            position.qty.toPlainString());
                    return Optional.of(new Decision.TakeProfitFull(
                            position.symbol, position.qty, reason));
                }
            }
            double nextTp = position.currentTpPct + exit.trailingTpStepPctV();
            String reason = String.format("TP partial %.0f%% ở pnl=%.2f%% (trend còn thuận, giữ phần còn lại)",
                    ratio * 100, pnlPct);
            log.info("[POS:TP_PARTIAL] {} trend thuận ({}) → bán {}, tpPct {} → {}, breakeven={}",
                    position.symbol, trend, sellQty.toPlainString(),
                    fmt(position.currentTpPct), fmt(nextTp),
                    exit.breakevenAfterPartialTpV());
            position.currentTpPct = nextTp;
            position.tpLevelsHit++;
            if (exit.breakevenAfterPartialTpV()) position.currentSlPct = 0.0;
            return Optional.of(new Decision.TakeProfitPartial(
                    position.symbol, sellQty, nextTp, reason));
        } else {
            String reason = String.format("TP full ở pnl=%.2f%% (trend=%s → chốt toàn bộ)",
                    pnlPct, trend);
            log.info("[POS:TP_FULL] {} trend={} → chốt toàn bộ qty={}",
                    position.symbol, trend, position.qty.toPlainString());
            return Optional.of(new Decision.TakeProfitFull(
                    position.symbol, position.qty, reason));
        }
    }

    private Optional<Decision> evaluateStopLoss(Position position, BarSeries series,
                                                double pnlPct, AppConfig.Exit exit) {
        position.dropCheckCount++;
        int n = position.dropCheckCount;
        int max = exit.maxDropChecksV();

        if (n >= max) {
            String reason = String.format("SL sau %d lần check (pnl=%.2f%% ≤ -%.2f%%) → bán dứt khoát",
                    n, pnlPct, exit.stopLossPctV());
            log.warn("[POS:SL_FORCED] {} đã check {}/{} lần → bán qty={}",
                    position.symbol, n, max, position.qty.toPlainString());
            return Optional.of(new Decision.StopLoss(position.symbol, position.qty, reason));
        }

        Trend trend = trendIndicators.classify(series);
        boolean recovering = trendIndicators.isRecovering(series);
        boolean trendAgainstUs = position.isLong()
                ? trend == Trend.DOWNTREND && !recovering
                : trend == Trend.UPTREND && !recovering;
        if (trendAgainstUs) {
            String reason = String.format("SL lần %d/%d, trend ngược hướng không hồi → bán ngay (pnl=%.2f%%)",
                    n, max, pnlPct);
            log.warn("[POS:SL_TREND_BAD] {} lần {}/{} trend={} không hồi → bán qty={}",
                    position.symbol, n, max, trend, position.qty.toPlainString());
            return Optional.of(new Decision.StopLoss(position.symbol, position.qty, reason));
        }

        log.info("[POS:SL_HOLD] {} pnl={}% dropCheck={}/{} trend={} recovering={} → HOLD",
                position.symbol, fmt(pnlPct), n, max, trend, recovering);
        return Optional.empty();
    }

    public double computePnlPct(Position position, BigDecimal currentPrice) {
        BigDecimal diff = position.isLong()
                ? currentPrice.subtract(position.entryPrice)
                : position.entryPrice.subtract(currentPrice);
        double pricePct = diff.divide(position.entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .doubleValue();
        int lev = position.leverage == null ? 1 : position.leverage;
        return pricePct * lev;
    }

    private static String fmt(double d) { return String.format("%.2f", d); }
}
