package vn.tamtd.bot.report;

import java.time.LocalDate;
import java.util.Map;

/**
 * Tổng hợp số liệu trade trong 1 khoảng thời gian.
 *
 * <p>Tất cả PnL tính bằng USDT. {@code grossLossUsdt}, {@code avgLossUsdt}, {@code worstTradeUsdt}
 * lưu giá trị âm (giữ dấu của loss thật). {@code profitFactor} = {@code grossProfit / |grossLoss|};
 * khi không có loss → {@link Double#POSITIVE_INFINITY}.
 *
 * <p>Generated bởi {@link ReportGenerator}, format bởi {@link ReportFormatter}.
 */
public record PeriodReport(
        Kind kind,
        LocalDate fromInclusive,
        LocalDate toInclusive,
        int totalTrades,
        int wins,
        int losses,
        int breakeven,
        double grossProfitUsdt,
        double grossLossUsdt,
        double netPnlUsdt,
        double winRatePct,
        double profitFactor,
        double avgWinUsdt,
        double avgLossUsdt,
        double bestTradeUsdt,
        double worstTradeUsdt,
        String bestSymbol,
        String worstSymbol,
        /** Đếm số lệnh theo loại đóng (TP_FULL, TP_PARTIAL, STOP_LOSS, REBALANCE, KILL_SWITCH). */
        Map<String, Integer> countByType,
        /** Đếm theo nguồn entry (WATCHLIST, SCANNER, REHYDRATED). */
        Map<String, Integer> countBySource,
        /** Net PnL gom theo symbol. */
        Map<String, Double> netBySymbol,
        /** Đa số trade là Spot hay Futures (lấy mode dominant). */
        String dominantMode
) {

    public enum Kind { DAILY, WEEKLY, MONTHLY, CUSTOM }

    public boolean isEmpty() { return totalTrades == 0; }
}
