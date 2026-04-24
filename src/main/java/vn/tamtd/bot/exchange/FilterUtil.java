package vn.tamtd.bot.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Các helper làm tròn qty/price theo {@link SymbolFilter} của Binance.
 *
 * <p>Quy tắc Binance:
 * <ul>
 *   <li>qty phải ≥ {@code lotMinQty}, ≤ {@code lotMaxQty}, và làm tròn XUỐNG theo step</li>
 *   <li>price phải ≥ {@code priceMin}, ≤ {@code priceMax}, và làm tròn theo tick</li>
 *   <li>{@code qty × price} ≥ {@code minNotional}</li>
 * </ul>
 */
public final class FilterUtil {

    private FilterUtil() {}

    /**
     * Làm tròn qty XUỐNG theo step. VD step=0.001, qty=1.2345 -> 1.234.
     * Trả null nếu kết quả nhỏ hơn lotMinQty.
     */
    public static BigDecimal roundQtyDown(BigDecimal qty, SymbolFilter f) {
        if (qty == null || f == null) return null;
        BigDecimal step = f.lotStepSize();
        if (step.signum() == 0) return qty;
        BigDecimal steps = qty.divide(step, 0, RoundingMode.DOWN);
        BigDecimal rounded = steps.multiply(step).stripTrailingZeros();
        if (rounded.compareTo(f.lotMinQty()) < 0) return null;
        if (rounded.compareTo(f.lotMaxQty()) > 0) return f.lotMaxQty();
        return rounded.setScale(step.stripTrailingZeros().scale() < 0 ? 0
                : step.stripTrailingZeros().scale(), RoundingMode.UNNECESSARY);
    }

    /**
     * Làm tròn price theo tick. Default DOWN cho SELL-like, UP cho BUY-like.
     */
    public static BigDecimal roundPrice(BigDecimal price, SymbolFilter f, RoundingMode mode) {
        if (price == null || f == null) return null;
        BigDecimal tick = f.priceTickSize();
        if (tick.signum() == 0) return price;
        BigDecimal ticks = price.divide(tick, 0, mode);
        BigDecimal rounded = ticks.multiply(tick).stripTrailingZeros();
        if (rounded.compareTo(f.priceMin()) < 0) return f.priceMin();
        if (rounded.compareTo(f.priceMax()) > 0) return f.priceMax();
        int scale = tick.stripTrailingZeros().scale();
        return rounded.setScale(Math.max(0, scale), RoundingMode.UNNECESSARY);
    }

    /**
     * Kiểm tra qty × price đã đạt minNotional chưa.
     */
    public static boolean meetsMinNotional(BigDecimal qty, BigDecimal price, SymbolFilter f) {
        if (qty == null || price == null || f == null) return false;
        BigDecimal notional = qty.multiply(price);
        return notional.compareTo(f.minNotional()) >= 0;
    }

    /**
     * Từ quoteAmount (USDT muốn tiêu) và price hiện tại, tính qty thoả LOT_SIZE.
     * Trả null nếu quoteAmount không đủ cover minNotional hoặc < lotMinQty.
     */
    public static BigDecimal qtyFromQuote(BigDecimal quoteAmount, BigDecimal price, SymbolFilter f) {
        if (quoteAmount == null || price == null || price.signum() == 0) return null;
        BigDecimal rawQty = quoteAmount.divide(price, 12, RoundingMode.DOWN);
        BigDecimal qty = roundQtyDown(rawQty, f);
        if (qty == null) return null;
        if (!meetsMinNotional(qty, price, f)) return null;
        return qty;
    }
}
