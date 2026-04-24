package vn.tamtd.bot.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.notify.Notifier;
import vn.tamtd.bot.notify.NotifyEvent;
import vn.tamtd.bot.storage.Position;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Đối với futures: nếu giá đi ngược quá {@code risk.liquidationBufferPct}% khoảng cách đến liquidation,
 * bán khẩn cấp để tránh mất hết margin. Sớm hơn SL thường nếu leverage cao.
 *
 * <p>Công thức buffer: với LONG entry=E, liquidation=L (L<E), current=C
 *   distanceLost = (E - C) / (E - L) × 100
 * nếu distanceLost > 100% - liquidationBufferPct → trả về StopLoss decision.
 */
public final class LiquidationGuard {

    private static final Logger log = LoggerFactory.getLogger(LiquidationGuard.class);
    private static final Duration ALERT_COOLDOWN = Duration.ofMinutes(30);

    private final ConfigRegistry configRegistry;
    private final Notifier notifier;
    private final Map<String, Instant> alertedAt = new HashMap<>();

    public LiquidationGuard(ConfigRegistry configRegistry, Notifier notifier) {
        this.configRegistry = configRegistry;
        this.notifier = notifier;
    }

    /** Evaluate 1 futures position; nếu trả Decision tức là cần đóng khẩn. */
    public Optional<Decision> evaluate(Position p, BigDecimal currentPrice) {
        AppConfig cfg = configRegistry.current();
        if (!cfg.mode().isFutures()) return Optional.empty();
        if (p.liquidationPrice == null || p.liquidationPrice.signum() == 0) return Optional.empty();

        double entry = p.entryPrice.doubleValue();
        double liq = p.liquidationPrice.doubleValue();
        double cur = currentPrice.doubleValue();
        double distanceLostPct;
        if (p.isLong()) {
            // Với LONG: liq < entry. Giá càng về liq → %lost càng cao.
            if (entry <= liq) return Optional.empty();
            distanceLostPct = (entry - cur) / (entry - liq) * 100.0;
        } else {
            // SHORT: liq > entry. Giá càng lên liq → %lost càng cao.
            if (liq <= entry) return Optional.empty();
            distanceLostPct = (cur - entry) / (liq - entry) * 100.0;
        }
        double limit = 100.0 - cfg.risk().liquidationBufferPct();
        if (distanceLostPct < limit) return Optional.empty();

        log.warn("[LIQ_GUARD] {} đã mất {}% khoảng cách đến liquidation (entry={} liq={} cur={}) > ngưỡng {}% → đóng khẩn",
                p.symbol, String.format("%.1f", distanceLostPct),
                p.entryPrice, p.liquidationPrice, currentPrice,
                String.format("%.1f", limit));
        maybeAlert(p, distanceLostPct);
        String reason = String.format("Liquidation buffer: mất %.1f%% khoảng cách đến liq (buffer=%.1f%%)",
                distanceLostPct, cfg.risk().liquidationBufferPct());
        return Optional.of(new Decision.StopLoss(p.symbol, p.qty, reason));
    }

    private void maybeAlert(Position p, double distanceLostPct) {
        Instant last = alertedAt.get(p.symbol);
        Instant now = Instant.now();
        if (last != null && Duration.between(last, now).compareTo(ALERT_COOLDOWN) < 0) return;
        alertedAt.put(p.symbol, now);
        notifier.send(NotifyEvent.LIQUIDATION_WARN, String.format(
                "%s gần liquidation: mất %.1f%% khoảng cách (entry=%s liq=%s)",
                p.symbol, distanceLostPct,
                p.entryPrice, p.liquidationPrice));
    }
}
