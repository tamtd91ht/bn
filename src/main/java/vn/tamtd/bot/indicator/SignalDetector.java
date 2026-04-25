package vn.tamtd.bot.indicator;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.num.Num;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;

/**
 * Nhận diện tín hiệu scanner-level từ {@link BarSeries}:
 * UPTREND_EMERGING / BOTTOM_REVERSAL / STRONG_WAVE.
 */
public final class SignalDetector {

    private final ConfigRegistry configRegistry;

    public SignalDetector(ConfigRegistry configRegistry) {
        this.configRegistry = configRegistry;
    }

    public boolean isUptrendEmerging(BarSeries s) {
        AppConfig.Signals sig = configRegistry.current().signals();
        int emaShort = sig.emaShort();
        int volMa = 20;
        if (s.getBarCount() < emaShort + 3) return false;

        int last = s.getEndIndex();
        ClosePriceIndicator close = new ClosePriceIndicator(s);
        EMAIndicator ema = new EMAIndicator(close, emaShort);
        Indicator<Num> volume = new VolumeIndicator(s);
        SMAIndicator volSma = new SMAIndicator(volume, volMa);

        double c1 = close.getValue(last).doubleValue();
        double c0 = close.getValue(last - 1).doubleValue();
        double e1 = ema.getValue(last).doubleValue();
        double e0 = ema.getValue(last - 1).doubleValue();

        boolean crossedUp = c0 < e0 && c1 > e1;
        double slope = TrendIndicators.slopeOf(ema, last, sig.slopeConfirmBars());
        boolean slopeUp = slope > 0;
        double v = volume.getValue(last).doubleValue();
        double vAvg = volSma.getValue(last).doubleValue();
        boolean volSpike = v > vAvg * 1.2;

        return crossedUp && slopeUp && volSpike;
    }

    public boolean isBottomReversal(BarSeries s) {
        AppConfig.Signals sig = configRegistry.current().signals();
        int period = sig.rsiPeriod();
        if (s.getBarCount() < period + 10) return false;

        int last = s.getEndIndex();
        ClosePriceIndicator close = new ClosePriceIndicator(s);
        RSIIndicator rsi = new RSIIndicator(close, period);
        Indicator<Num> volume = new VolumeIndicator(s);
        SMAIndicator volSma = new SMAIndicator(volume, 20);

        double rsiNow = rsi.getValue(last).doubleValue();
        if (rsiNow < sig.rsiOversoldExit()) return false;

        boolean hadOversold = false;
        for (int i = Math.max(0, last - 5); i < last; i++) {
            if (rsi.getValue(i).doubleValue() < sig.rsiOversoldEnter()) {
                hadOversold = true;
                break;
            }
        }
        if (!hadOversold) return false;

        double recentLow = s.getBar(last).getLowPrice().doubleValue();
        double swingLow = Double.MAX_VALUE;
        for (int i = Math.max(0, last - 10); i < last; i++) {
            swingLow = Math.min(swingLow, s.getBar(i).getLowPrice().doubleValue());
        }
        boolean higherLow = recentLow > swingLow;

        double v = volume.getValue(last).doubleValue();
        double vAvg = volSma.getValue(last).doubleValue();
        boolean volOk = v > vAvg;

        return higherLow && volOk;
    }

    public boolean isStrongWave(BarSeries s4h) {
        AppConfig.Signals sig = configRegistry.current().signals();
        int emaShort = sig.emaShort();
        int rsiPeriod = sig.rsiPeriod();
        if (s4h.getBarCount() < Math.max(20, rsiPeriod + 3)) return false;
        int last = s4h.getEndIndex();
        ClosePriceIndicator close = new ClosePriceIndicator(s4h);
        EMAIndicator ema = new EMAIndicator(close, emaShort);
        Indicator<Num> vol = new VolumeIndicator(s4h);
        SMAIndicator volSma = new SMAIndicator(vol, 20);

        double c1 = close.getValue(last).doubleValue();
        double c0 = close.getValue(last - 1).doubleValue();
        double pctChange = (c1 - c0) / c0 * 100.0;

        boolean bigPump = pctChange > 5.0;
        boolean aboveEma = c1 > ema.getValue(last).doubleValue();
        boolean volSpike = vol.getValue(last).doubleValue() > volSma.getValue(last).doubleValue() * 2;
        if (!(bigPump && aboveEma && volSpike)) return false;

        // Filter: tránh catch đỉnh sóng. Nếu RSI 4h đã quá overbought → reject.
        // Logic: pump > 5% trong 1 nến 4h thường đẩy RSI vọt qua 70 - đây thường
        // là điểm cuối của xu hướng tăng, sau đó pullback. APE 24/04: pump xong
        // bot mua đỉnh → -7.15%. Filter này tránh exact pattern đó.
        int rsiMax = sig.strongWaveRsiMaxV();
        if (rsiMax > 0) {
            RSIIndicator rsi = new RSIIndicator(close, rsiPeriod);
            double rsi4h = rsi.getValue(last).doubleValue();
            if (rsi4h > rsiMax) {
                return false;
            }
        }
        return true;
    }
}
