package vn.tamtd.bot.exchange;

import java.math.BigDecimal;

/**
 * Giao diện đặt lệnh - tách khỏi {@link ExchangeClient} vì signature khác nhau
 * giữa spot (quote-based buy) và futures (qty-based + side + reduceOnly).
 *
 * <p>Response trả về JSON thô, caller parse thành {@link vn.tamtd.bot.executor.OrderFillResult}.
 */
public interface OrderGateway {

    /** Vào lệnh LONG (hoặc BUY spot). */
    String openLong(OpenRequest request);

    /** Vào lệnh SHORT (chỉ futures). Spot impl có thể throw UnsupportedOperationException. */
    String openShort(OpenRequest request);

    /** Đóng vị thế hoặc bán coin (spot). */
    String close(CloseRequest request);

    /** Đặt leverage cho symbol - chỉ cần cho futures. Spot no-op. */
    default void setLeverage(String symbol, int leverage) {}

    /** Đặt marginType ISOLATED/CROSSED - chỉ futures. Spot no-op. */
    default void setMarginType(String symbol, String marginType) {}

    // ==== Request types ====

    /**
     * @param symbol symbol (BTCUSDT)
     * @param quoteAmountUsdt USDT muốn chi (spot dùng trực tiếp; futures × leverage = notional)
     * @param qty qty explicit - ưu tiên hơn quoteAmountUsdt nếu != null (futures dùng)
     * @param clientOrderId optional
     */
    record OpenRequest(
            String symbol,
            BigDecimal quoteAmountUsdt,
            BigDecimal qty,
            String clientOrderId
    ) {
        public static OpenRequest byQuote(String symbol, BigDecimal usdt, String clientOrderId) {
            return new OpenRequest(symbol, usdt, null, clientOrderId);
        }
        public static OpenRequest byQty(String symbol, BigDecimal qty, String clientOrderId) {
            return new OpenRequest(symbol, null, qty, clientOrderId);
        }
    }

    /**
     * @param side "LONG" | "SHORT" - phải khớp chiều vị thế đang đóng (futures reduceOnly).
     *             Với spot mặc định SELL.
     */
    record CloseRequest(
            String symbol,
            BigDecimal qty,
            String side,
            String clientOrderId,
            boolean reduceOnly
    ) {
        public static CloseRequest spotSell(String symbol, BigDecimal qty, String clientOrderId) {
            return new CloseRequest(symbol, qty, "LONG", clientOrderId, false);
        }
        public static CloseRequest futuresClose(String symbol, BigDecimal qty,
                                                String openedSide, String clientOrderId) {
            return new CloseRequest(symbol, qty, openedSide, clientOrderId, true);
        }
    }
}
