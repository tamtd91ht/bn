package vn.tamtd.bot.storage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Position đang mở - lưu trong state.json dưới key {@code positions.{symbol}}.
 *
 * <p>Dùng chung cho cả Spot và Futures. Các field futures-only (side, leverage, liquidationPrice…)
 * được {@code null} cho spot. Composition chứ không sealed để JSON schema đơn giản
 * (Jackson không cần polymorphic deserializer).
 *
 * <p>Source:
 * <ul>
 *   <li>WATCHLIST - vào từ danh sách cố định của user</li>
 *   <li>SCANNER - vào từ reserveFund sau khi scanner phát hiện opportunity</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Position {
    public String symbol;
    public BigDecimal qty;           // qty còn lại (sau partial TP có thể giảm)
    public BigDecimal entryPrice;
    public Instant entryAt;
    public String source;            // WATCHLIST / SCANNER

    public int dropCheckCount;
    public int tpLevelsHit;
    public double currentTpPct;
    public double currentSlPct;

    // ======== FUTURES-ONLY (null nếu spot) ========

    /** "LONG" | "SHORT" | null (spot = null ~ LONG). */
    public String side;

    /** Đòn bẩy effective lúc mở vị thế. null cho spot. */
    public Integer leverage;

    /** Notional USDT = qty × entryPrice (dùng tính exposure, margin...). */
    public BigDecimal notionalUsdt;

    /** Margin USDT thực tế nạp cho vị thế = notional / leverage (isolated). null cho spot. */
    public BigDecimal marginUsdt;

    /** Giá liquidation snapshot khi vào lệnh. Cập nhật định kỳ từ positionRisk. null cho spot. */
    public BigDecimal liquidationPrice;

    public Position() {}

    /** Constructor spot. */
    public static Position spotEntry(String symbol, BigDecimal qty, BigDecimal entryPrice,
                                     String source, double tpPct, double slPct) {
        Position p = baseEntry(symbol, qty, entryPrice, source, tpPct, slPct);
        p.side = "LONG";
        p.leverage = 1;
        p.notionalUsdt = qty.multiply(entryPrice);
        p.marginUsdt = p.notionalUsdt;
        return p;
    }

    /** Constructor futures. */
    public static Position futuresEntry(String symbol, String side,
                                        BigDecimal qty, BigDecimal entryPrice,
                                        int leverage, BigDecimal liquidationPrice,
                                        String source, double tpPct, double slPct) {
        Position p = baseEntry(symbol, qty, entryPrice, source, tpPct, slPct);
        p.side = side;
        p.leverage = leverage;
        p.notionalUsdt = qty.multiply(entryPrice);
        p.marginUsdt = p.notionalUsdt.divide(BigDecimal.valueOf(leverage),
                8, java.math.RoundingMode.HALF_UP);
        p.liquidationPrice = liquidationPrice;
        return p;
    }

    /** Backward-compat helper: spot entry giống API cũ. */
    public static Position newEntry(String symbol, BigDecimal qty, BigDecimal entryPrice,
                                    String source, double tpPct, double slPct) {
        return spotEntry(symbol, qty, entryPrice, source, tpPct, slPct);
    }

    private static Position baseEntry(String symbol, BigDecimal qty, BigDecimal entryPrice,
                                      String source, double tpPct, double slPct) {
        Position p = new Position();
        p.symbol = symbol;
        p.qty = qty;
        p.entryPrice = entryPrice;
        p.entryAt = Instant.now();
        p.source = source;
        p.dropCheckCount = 0;
        p.tpLevelsHit = 0;
        p.currentTpPct = tpPct;
        p.currentSlPct = slPct;
        return p;
    }

    /** true nếu vị thế LONG (hoặc spot ≡ LONG). */
    public boolean isLong() { return side == null || "LONG".equalsIgnoreCase(side); }

    /** true nếu đây là vị thế futures (đã có leverage / liquidation info). */
    public boolean isFutures() { return leverage != null && leverage > 1
            || liquidationPrice != null; }
}
