package vn.tamtd.bot.indicator;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.statistics.SimpleLinearRegressionIndicator;
import org.ta4j.core.num.Num;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;

/**
 * Xác định xu hướng / sideway / hồi phục từ {@link BarSeries}, dựa trên config signals.
 * Đọc config qua {@link ConfigRegistry#current()} mỗi lần để tự động nhận snapshot mới
 * sau hot-reload.
 */
public final class TrendIndicators {

    private final ConfigRegistry configRegistry;

    public TrendIndicators(ConfigRegistry configRegistry) {
        this.configRegistry = configRegistry;
    }

    public enum Trend { UPTREND, DOWNTREND, SIDEWAY }

    public Trend classify(BarSeries s) {
        AppConfig.Signals sig = configRegistry.current().signals();
        if (s.getBarCount() < sig.emaLong() + 5) return Trend.SIDEWAY;
        int last = s.getEndIndex();
        ClosePriceIndicator close = new ClosePriceIndicator(s);
        EMAIndicator emaS = new EMAIndicator(close, sig.emaShort());
        EMAIndicator emaL = new EMAIndicator(close, sig.emaLong());

        double price = close.getValue(last).doubleValue();
        double es = emaS.getValue(last).doubleValue();
        double el = emaL.getValue(last).doubleValue();

        double gapPct = Math.abs(es - el) / price * 100.0;
        if (gapPct < sig.sidewayEmaGapPct()) return Trend.SIDEWAY;

        double slope = slopeOf(emaS, last, sig.slopeConfirmBars());
        if (price > es && es > el && slope > 0) return Trend.UPTREND;
        if (price < es && es < el && slope < 0) return Trend.DOWNTREND;
        return Trend.SIDEWAY;
    }

    public boolean isUptrend(BarSeries s)   { return classify(s) == Trend.UPTREND; }
    public boolean isDowntrend(BarSeries s) { return classify(s) == Trend.DOWNTREND; }
    public boolean isSideway(BarSeries s)   { return classify(s) == Trend.SIDEWAY; }

    public boolean isRecovering(BarSeries s) {
        AppConfig.Signals sig = configRegistry.current().signals();
        if (s.getBarCount() < 3) return false;
        int last = s.getEndIndex();
        ClosePriceIndicator close = new ClosePriceIndicator(s);
        VolumeIndicator vol = new VolumeIndicator(s);
        RSIIndicator rsi = new RSIIndicator(close, sig.rsiPeriod());

        boolean closeUp = close.getValue(last).doubleValue() > close.getValue(last - 1).doubleValue();
        boolean volUp = vol.getValue(last).doubleValue() > vol.getValue(last - 1).doubleValue();
        boolean rsiUp = rsi.getValue(last).doubleValue() > rsi.getValue(last - 1).doubleValue();
        return closeUp && volUp && rsiUp;
    }

    public double rsi(BarSeries s) {
        AppConfig.Signals sig = configRegistry.current().signals();
        if (s.getBarCount() < sig.rsiPeriod() + 1) return 50.0;
        ClosePriceIndicator close = new ClosePriceIndicator(s);
        RSIIndicator rsi = new RSIIndicator(close, sig.rsiPeriod());
        return rsi.getValue(s.getEndIndex()).doubleValue();
    }

    public double lastClose(BarSeries s) {
        return s.getBar(s.getEndIndex()).getClosePrice().doubleValue();
    }

    static double slopeOf(Indicator<Num> indicator, int lastIndex, int n) {
        if (lastIndex < n) return 0;
        SimpleLinearRegressionIndicator slr = new SimpleLinearRegressionIndicator(
                indicator, n, SimpleLinearRegressionIndicator.SimpleLinearRegressionType.SLOPE);
        return slr.getValue(lastIndex).doubleValue();
    }
}
