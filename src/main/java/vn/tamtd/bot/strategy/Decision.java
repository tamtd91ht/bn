package vn.tamtd.bot.strategy;

import java.math.BigDecimal;

/**
 * Quyết định do strategy sinh ra trong 1 tick. M3 log ra; M4 OrderExecutor sẽ thực thi.
 */
public sealed interface Decision {

    String symbol();
    String reason();

    /** Giữ nguyên, không làm gì. Không emit vào danh sách output. */
    record Hold(String symbol, String reason) implements Decision {}

    /** Vào lệnh BUY MARKET bằng quoteAmount USDT. */
    record EntryBuy(String symbol, BigDecimal quoteAmount, String source, String reason)
            implements Decision {}

    /** Bán partial position (vd 50% qty) khi TP lần 1 và trend tăng tiếp. */
    record TakeProfitPartial(String symbol, BigDecimal qtyToSell, double nextTpPct, String reason)
            implements Decision {}

    /** Bán toàn bộ position khi TP và trend không tăng / sideway. */
    record TakeProfitFull(String symbol, BigDecimal qtyToSell, String reason)
            implements Decision {}

    /** Bán toàn bộ position vì cắt lỗ (SL 2-check đã đủ điều kiện). */
    record StopLoss(String symbol, BigDecimal qtyToSell, String reason)
            implements Decision {}

    /** Bán coin để nạp USDT về reserveFund cho opportunity khác. */
    record RebalanceSell(String symbol, BigDecimal qtyToSell, String reason)
            implements Decision {}

    /** Kill-switch: bán tất cả vị thế. */
    record KillSwitchSellAll(String symbol, BigDecimal qtyToSell, String reason)
            implements Decision {}
}
