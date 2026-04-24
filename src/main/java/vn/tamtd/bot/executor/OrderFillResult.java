package vn.tamtd.bot.executor;

import java.math.BigDecimal;

/**
 * Parse kết quả 1 order từ response Binance (MARKET order).
 *
 * <p>Binance trả các field quan trọng:
 * <ul>
 *   <li>{@code status}: NEW / PARTIALLY_FILLED / FILLED / REJECTED ...</li>
 *   <li>{@code executedQty}: tổng qty đã khớp</li>
 *   <li>{@code cummulativeQuoteQty}: tổng USDT đã chi/nhận</li>
 * </ul>
 *
 * <p>Giá trung bình = cummulativeQuoteQty / executedQty.
 */
public record OrderFillResult(
        String symbol,
        String side,
        String status,
        String clientOrderId,
        long orderId,
        BigDecimal executedQty,
        BigDecimal cummulativeQuoteQty,
        BigDecimal avgPrice
) {
    public boolean isFilled() {
        return "FILLED".equals(status);
    }

    public boolean isRejected() {
        return "REJECTED".equals(status) || "EXPIRED".equals(status) || "CANCELED".equals(status);
    }
}
