package vn.tamtd.bot.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.exchange.BinanceFuturesClient;
import vn.tamtd.bot.exchange.ExchangeClient;
import vn.tamtd.bot.notify.Notifier;
import vn.tamtd.bot.notify.NotifyEvent;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Chặn mở LONG mới khi funding rate hiện tại > ngưỡng xấu.
 * Funding dương = LONG trả SHORT mỗi 8h.
 * <p>Chỉ áp dụng cho futures. Spot: {@link #allowsLong} luôn trả true.
 */
public final class FundingRateGuard {

    private static final Logger log = LoggerFactory.getLogger(FundingRateGuard.class);
    private static final Duration ALERT_COOLDOWN = Duration.ofHours(8);

    private final ConfigRegistry configRegistry;
    private final ExchangeClient exchangeClient;
    private final Notifier notifier;
    private final Map<String, Instant> alertedAt = new HashMap<>();

    public FundingRateGuard(ConfigRegistry configRegistry,
                            ExchangeClient exchangeClient,
                            Notifier notifier) {
        this.configRegistry = configRegistry;
        this.exchangeClient = exchangeClient;
        this.notifier = notifier;
    }

    /** @return true = được phép mở LONG; false = funding quá xấu, skip. */
    public boolean allowsLong(String symbol) {
        AppConfig cfg = configRegistry.current();
        if (!cfg.mode().isFutures()) return true;
        AppConfig.Risk risk = cfg.risk();
        if (!risk.fundingGuardEnabled()) return true;
        if (!(exchangeClient instanceof BinanceFuturesClient fc)) return true;

        try {
            BigDecimal r = fc.currentFundingRate(symbol);
            double rateAbs = r.doubleValue() * 100; // % per 8h
            double threshold = risk.fundingMaxAcceptablePctPer8h();
            if (rateAbs > threshold) {
                log.warn("[FUNDING] {} rate={}%/8h > threshold={}%/8h → skip LONG",
                        symbol, String.format("%.4f", rateAbs), threshold);
                maybeAlert(symbol, rateAbs, threshold);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[FUNDING] Fetch funding {} lỗi: {}", symbol, e.getMessage());
            // Fail-open: không chặn trade vì data chưa lấy được
            return true;
        }
    }

    private void maybeAlert(String symbol, double rate, double threshold) {
        Instant last = alertedAt.get(symbol);
        Instant now = Instant.now();
        if (last != null && Duration.between(last, now).compareTo(ALERT_COOLDOWN) < 0) return;
        alertedAt.put(symbol, now);
        notifier.send(NotifyEvent.FUNDING_HIGH, String.format(
                "Funding %s = %.4f%%/8h (trần %.2f%%) → bỏ qua LONG mới",
                symbol, rate, threshold));
    }
}
