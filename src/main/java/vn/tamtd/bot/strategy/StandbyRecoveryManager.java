package vn.tamtd.bot.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.storage.BotState;
import vn.tamtd.bot.storage.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Time-based recovery cho vị thế STANDBY.
 *
 * <p>Sau {@code standbyRecoveryHours}, nếu PnL% >= {@code standbyMinRecoveryPnlPct},
 * sinh {@link Decision.StandbyRecover} để đóng sớm và giải phóng standby fund về pool.
 * TP/SL bình thường vẫn hoạt động song song (PositionManager handle).
 */
public final class StandbyRecoveryManager {

    private static final Logger log = LoggerFactory.getLogger(StandbyRecoveryManager.class);

    private final ConfigRegistry configRegistry;

    public StandbyRecoveryManager(ConfigRegistry configRegistry) {
        this.configRegistry = configRegistry;
    }

    /**
     * Kiểm tra từng vị thế STANDBY. Trả về quyết định recovery đầu tiên đủ điều kiện.
     * Chỉ 1 recovery per tick để tránh close quá nhiều cùng lúc.
     */
    public Optional<Decision.StandbyRecover> evaluate(BotState state,
                                                       Map<String, BigDecimal> prices) {
        AppConfig config = configRegistry.current();
        if (!config.capital().standbyEnabled()) return Optional.empty();

        int recoveryHours = config.capital().standbyRecoveryHoursV();
        double minPnlPct  = config.capital().standbyMinRecoveryPnlPctV();

        for (Position p : state.positions.values()) {
            if (!"STANDBY".equals(p.source)) continue;
            if (p.entryAt == null) continue;

            long ageHours = Duration.between(p.entryAt, Instant.now()).toHours();
            if (ageHours < recoveryHours) continue;

            BigDecimal price = prices.get(p.symbol);
            if (price == null) continue;

            double pnlPct = computePnlPct(p, price);
            if (pnlPct < minPnlPct) {
                log.debug("[STANDBY-RECOVER] {} age={}h pnlPct={}% < minPnl={}% - chưa đủ điều kiện",
                        p.symbol, ageHours, String.format("%.2f", pnlPct), minPnlPct);
                continue;
            }

            String reason = String.format(
                    "Standby recovery: age=%dh pnl=%+.2f%% ≥ %.1f%% → thu hồi standby fund",
                    ageHours, pnlPct, minPnlPct);
            log.info("[STANDBY-RECOVER] {} age={}h pnl={}% → đóng sớm", p.symbol, ageHours,
                    String.format("%+.2f", pnlPct));
            return Optional.of(new Decision.StandbyRecover(p.symbol, p.qty, reason));
        }
        return Optional.empty();
    }

    private double computePnlPct(Position p, BigDecimal currentPrice) {
        BigDecimal diff = p.isLong()
                ? currentPrice.subtract(p.entryPrice)
                : p.entryPrice.subtract(currentPrice);
        return diff.divide(p.entryPrice, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();
    }
}
