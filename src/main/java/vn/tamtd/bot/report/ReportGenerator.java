package vn.tamtd.bot.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Quét file {@code data/trades/YYYY-MM-DD.jsonl} trong khoảng ngày, tổng hợp thành {@link PeriodReport}.
 *
 * <p>Format trade JSONL ghi bởi {@link vn.tamtd.bot.executor.OrderExecutor}:
 * <pre>
 * {
 *   "ts": "...", "mode": "SPOT", "symbol": "DOGEUSDT", "type": "TP_FULL",
 *   "qty": "...", "entryPrice": "...", "exitPrice": "...",
 *   "realizedPnlUsdt": "0.5000", "pnlPct": "5.0000",
 *   "source": "WATCHLIST", "fullyClosed": true
 * }
 * </pre>
 *
 * <p>Số liệu numeric được serialize dưới dạng String trong JSONL (BigDecimal.toPlainString),
 * cần parse lại sang double.
 */
public final class ReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportGenerator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Path dataDir;

    public ReportGenerator(Path dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * Sinh report cho {@code [fromInclusive, toInclusive]}. Nếu không có trade nào trong khoảng,
     * vẫn trả {@link PeriodReport} với {@code totalTrades=0} (caller có thể bỏ qua).
     */
    public PeriodReport generate(PeriodReport.Kind kind, LocalDate fromInclusive, LocalDate toInclusive) {
        if (toInclusive.isBefore(fromInclusive)) {
            throw new IllegalArgumentException("toInclusive < fromInclusive: " + fromInclusive + " → " + toInclusive);
        }

        int total = 0, wins = 0, losses = 0, breakeven = 0;
        double grossProfit = 0, grossLoss = 0;
        double bestTrade = Double.NEGATIVE_INFINITY, worstTrade = Double.POSITIVE_INFINITY;
        String bestSymbol = null, worstSymbol = null;
        Map<String, Integer> countByType = new TreeMap<>();
        Map<String, Integer> countBySource = new TreeMap<>();
        Map<String, Double> netBySymbol = new HashMap<>();
        Map<String, Integer> modeCount = new HashMap<>();

        Path tradesDir = dataDir.resolve("trades");
        for (LocalDate d = fromInclusive; !d.isAfter(toInclusive); d = d.plusDays(1)) {
            Path file = tradesDir.resolve(d + ".jsonl");
            if (!Files.exists(file)) continue;
            try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (line.isBlank()) continue;
                    Map<String, Object> rec;
                    try {
                        rec = MAPPER.readValue(line, MAP_TYPE);
                    } catch (Exception e) {
                        log.warn("[REPORT] Skip line lỗi parse trong {}: {}", file, e.getMessage());
                        continue;
                    }
                    String symbol = str(rec, "symbol");
                    String type = str(rec, "type");
                    String source = str(rec, "source");
                    String mode = str(rec, "mode");
                    double pnl = parseDouble(rec.get("realizedPnlUsdt"));
                    if (Double.isNaN(pnl)) continue;

                    total++;
                    if (pnl > 0) { wins++; grossProfit += pnl; }
                    else if (pnl < 0) { losses++; grossLoss += pnl; }
                    else breakeven++;

                    if (pnl > bestTrade) { bestTrade = pnl; bestSymbol = symbol; }
                    if (pnl < worstTrade) { worstTrade = pnl; worstSymbol = symbol; }
                    if (type != null) countByType.merge(type, 1, Integer::sum);
                    if (source != null) countBySource.merge(source, 1, Integer::sum);
                    if (symbol != null) netBySymbol.merge(symbol, pnl, Double::sum);
                    if (mode != null) modeCount.merge(mode, 1, Integer::sum);
                }
            } catch (IOException e) {
                log.warn("[REPORT] Đọc {} lỗi: {}", file, e.getMessage());
            }
        }

        if (total == 0) {
            return new PeriodReport(kind, fromInclusive, toInclusive,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    null, null,
                    Map.of(), Map.of(), Map.of(), "?");
        }

        double netPnl = grossProfit + grossLoss;
        double winRate = total == 0 ? 0 : (wins * 100.0) / total;
        double profitFactor = grossLoss == 0
                ? (grossProfit > 0 ? Double.POSITIVE_INFINITY : 0)
                : grossProfit / Math.abs(grossLoss);
        double avgWin = wins == 0 ? 0 : grossProfit / wins;
        double avgLoss = losses == 0 ? 0 : grossLoss / losses;

        String dominantMode = modeCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("?");

        return new PeriodReport(
                kind, fromInclusive, toInclusive,
                total, wins, losses, breakeven,
                grossProfit, grossLoss, netPnl,
                winRate, profitFactor,
                avgWin, avgLoss,
                bestTrade, worstTrade,
                bestSymbol, worstSymbol,
                countByType, countBySource,
                sortByValueDesc(netBySymbol),
                dominantMode
        );
    }

    private static Map<String, Double> sortByValueDesc(Map<String, Double> m) {
        Map<String, Double> sorted = new LinkedHashMap<>();
        m.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    private static String str(Map<String, Object> rec, String key) {
        Object v = rec.get(key);
        return v == null ? null : v.toString();
    }

    private static double parseDouble(Object v) {
        if (v == null) return Double.NaN;
        try { return Double.parseDouble(v.toString()); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    /**
     * Lưu report ra {@code data/reports/<kind>-YYYY-MM-DD.txt}. Tên file dùng ngày kết thúc
     * của period để dễ search ("hôm nay sinh report cho hôm qua" → tên file là ngày hôm qua).
     */
    public Path writeToFile(PeriodReport report, String formatted) throws IOException {
        Path reportsDir = dataDir.resolve("reports");
        Files.createDirectories(reportsDir);
        String name = report.kind().name().toLowerCase() + "-" + report.toInclusive() + ".txt";
        Path file = reportsDir.resolve(name);
        Files.writeString(file, formatted, StandardCharsets.UTF_8);
        return file;
    }
}
