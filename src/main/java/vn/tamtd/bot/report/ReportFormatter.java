package vn.tamtd.bot.report;

import java.util.Map;

/**
 * Format {@link PeriodReport} thành text gọn (dưới 4096 ký tự để gửi Telegram OK).
 *
 * <p>Layout fix: header → tổng PnL → win/loss → top symbol → breakdown by type/source.
 */
public final class ReportFormatter {

    private ReportFormatter() {}

    public static String format(PeriodReport r) {
        StringBuilder sb = new StringBuilder();
        sb.append(headerLine(r)).append('\n');
        sb.append(rangeLine(r)).append('\n');
        sb.append("──────────────────\n");
        if (r.isEmpty()) {
            sb.append("Không có trade nào trong khoảng này.\n");
            return sb.toString().stripTrailing();
        }
        sb.append(String.format("Tổng số lệnh đóng: %d (mode=%s)%n", r.totalTrades(), r.dominantMode()));
        sb.append(String.format("Win/Lose/BE      : %d / %d / %d (winRate %.1f%%)%n",
                r.wins(), r.losses(), r.breakeven(), r.winRatePct()));
        sb.append(String.format("Net PnL          : %s USDT%n", fmtSigned(r.netPnlUsdt())));
        sb.append(String.format("Gross profit/loss: +%.4f / %.4f%n", r.grossProfitUsdt(), r.grossLossUsdt()));
        sb.append(String.format("Profit factor    : %s%n", fmtPF(r.profitFactor())));
        sb.append(String.format("Avg win / loss   : +%.4f / %.4f%n", r.avgWinUsdt(), r.avgLossUsdt()));
        sb.append(String.format("Best / Worst     : %s @ %s  /  %s @ %s%n",
                fmtSigned(r.bestTradeUsdt()), nullSafe(r.bestSymbol()),
                fmtSigned(r.worstTradeUsdt()), nullSafe(r.worstSymbol())));

        if (!r.countByType().isEmpty()) {
            sb.append("──────────────────\n");
            sb.append("Theo loại đóng:\n");
            for (var e : r.countByType().entrySet()) {
                sb.append(String.format("  %-12s %d%n", e.getKey(), e.getValue()));
            }
        }
        if (!r.countBySource().isEmpty()) {
            sb.append("Theo nguồn entry:\n");
            for (var e : r.countBySource().entrySet()) {
                sb.append(String.format("  %-12s %d%n", e.getKey(), e.getValue()));
            }
        }
        if (!r.netBySymbol().isEmpty()) {
            sb.append("Top symbol (net PnL):\n");
            int shown = 0;
            for (Map.Entry<String, Double> e : r.netBySymbol().entrySet()) {
                if (shown >= 8) break;
                sb.append(String.format("  %-12s %s%n", e.getKey(), fmtSigned(e.getValue())));
                shown++;
            }
        }
        return sb.toString().stripTrailing();
    }

    private static String headerLine(PeriodReport r) {
        return switch (r.kind()) {
            case DAILY   -> "📊 BÁO CÁO NGÀY";
            case WEEKLY  -> "📊 BÁO CÁO TUẦN";
            case MONTHLY -> "📊 BÁO CÁO THÁNG";
            case CUSTOM  -> "📊 BÁO CÁO";
        };
    }

    private static String rangeLine(PeriodReport r) {
        if (r.fromInclusive().equals(r.toInclusive())) {
            return "Ngày: " + r.fromInclusive();
        }
        return "Khoảng: " + r.fromInclusive() + " → " + r.toInclusive();
    }

    private static String fmtSigned(double v) {
        return (v >= 0 ? "+" : "") + String.format("%.4f", v);
    }

    private static String fmtPF(double pf) {
        if (Double.isInfinite(pf)) return "∞ (no losses)";
        return String.format("%.2f", pf);
    }

    private static String nullSafe(String s) { return s == null ? "?" : s; }
}
