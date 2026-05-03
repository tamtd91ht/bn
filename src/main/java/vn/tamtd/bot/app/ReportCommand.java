package vn.tamtd.bot.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigLoader;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.report.PeriodReport;
import vn.tamtd.bot.report.ReportFormatter;
import vn.tamtd.bot.report.ReportGenerator;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.Callable;

/**
 * Sinh báo cáo PnL ad-hoc từ JSONL trades đã lưu, in ra console + lưu file
 * {@code data/reports/<kind>-<endDate>.txt}.
 *
 * <pre>
 *   bot report --period day             # hôm qua (D-1)
 *   bot report --period week            # 7 ngày trước (Mon→Sun nếu hôm nay là Mon, không thì rolling 7d)
 *   bot report --period month           # tháng trước
 *   bot report --from 2026-04-01 --to 2026-04-30
 * </pre>
 */
@CommandLine.Command(
        name = "report",
        description = "Sinh báo cáo PnL theo ngày/tuần/tháng từ data/trades/*.jsonl"
)
public final class ReportCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(ReportCommand.class);
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @CommandLine.Option(names = {"-c", "--config-dir"},
            description = "Thư mục config (mặc định: thư mục jar)")
    Path configDir;

    @CommandLine.Option(names = {"--period"},
            description = "day | week | month")
    String period;

    @CommandLine.Option(names = {"--from"}, description = "Ngày bắt đầu (yyyy-MM-dd)")
    String from;

    @CommandLine.Option(names = {"--to"}, description = "Ngày kết thúc inclusive (yyyy-MM-dd)")
    String to;

    @Override
    public Integer call() throws Exception {
        Path baseDir = configDir != null ? configDir : ConfigLoader.jarDir();
        ConfigRegistry registry = ConfigRegistry.bootstrap(baseDir);
        AppConfig config = registry.current();
        Path dataDir = Paths.get(config.storage().dataDir());
        ReportGenerator gen = new ReportGenerator(dataDir);

        PeriodReport.Kind kind;
        LocalDate fromDate, toDate;
        if (from != null && to != null) {
            kind = PeriodReport.Kind.CUSTOM;
            fromDate = LocalDate.parse(from);
            toDate = LocalDate.parse(to);
        } else if ("day".equalsIgnoreCase(period)) {
            kind = PeriodReport.Kind.DAILY;
            toDate = LocalDate.now(VN_ZONE).minusDays(1);
            fromDate = toDate;
        } else if ("week".equalsIgnoreCase(period)) {
            kind = PeriodReport.Kind.WEEKLY;
            LocalDate today = LocalDate.now(VN_ZONE);
            // Nếu hôm nay là Mon → cover tuần trước (Mon-Sun); nếu không → rolling 7 ngày tính cả hôm qua
            if (today.getDayOfWeek() == DayOfWeek.MONDAY) {
                toDate = today.minusDays(1);
                fromDate = today.minusDays(7);
            } else {
                toDate = today.minusDays(1);
                fromDate = toDate.minusDays(6);
            }
        } else if ("month".equalsIgnoreCase(period)) {
            kind = PeriodReport.Kind.MONTHLY;
            LocalDate today = LocalDate.now(VN_ZONE);
            // Tháng trước: ngày 1 đến cuối tháng trước
            fromDate = today.minusMonths(1).withDayOfMonth(1);
            toDate = today.withDayOfMonth(1).minusDays(1);
        } else {
            System.err.println("Cần --period day|week|month hoặc --from + --to");
            return 1;
        }

        PeriodReport report = gen.generate(kind, fromDate, toDate);
        String formatted = ReportFormatter.format(report);
        System.out.println(formatted);
        try {
            Path out = gen.writeToFile(report, formatted);
            System.out.println("\nĐã lưu: " + out.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Lưu file report lỗi: {}", e.getMessage());
        }
        return 0;
    }
}
