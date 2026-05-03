package vn.tamtd.bot.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.notify.NotifyEvent;
import vn.tamtd.bot.notify.Notifier;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scheduler tự động phát báo cáo theo ngày/tuần/tháng vào {@code scheduling.dailyReportHourVN} VN.
 *
 * <p>Cadence:
 * <ul>
 *   <li><b>Daily</b> mỗi ngày: cover ngày hôm trước (D-1).</li>
 *   <li><b>Weekly</b> sáng thứ Hai: cover Mon→Sun tuần trước.</li>
 *   <li><b>Monthly</b> ngày 1: cover toàn bộ tháng trước.</li>
 * </ul>
 *
 * <p>Cùng một thời điểm có thể fire 2-3 report (VD ngày 1 mà rơi vào thứ Hai → daily + weekly + monthly).
 * Mỗi report được gửi qua {@link Notifier} + log + lưu ra {@code data/reports/}.
 */
public final class ReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduler.class);
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ConfigRegistry configRegistry;
    private final ReportGenerator generator;
    private final Notifier notifier;
    private final ScheduledExecutorService executor;
    /** Ngày VN cuối cùng đã fire daily, tránh fire trùng nếu tick lệch giây. */
    private volatile LocalDate lastDailyFiredFor;

    public ReportScheduler(ConfigRegistry configRegistry,
                           ReportGenerator generator,
                           Notifier notifier) {
        this.configRegistry = configRegistry;
        this.generator = generator;
        this.notifier = notifier;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "report-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        // Tick mỗi 60s, kiểm tra "đã đến giờ báo cáo chưa" (idempotent qua lastDailyFiredFor)
        executor.scheduleAtFixedRate(this::tick, 30, 60, TimeUnit.SECONDS);
        AppConfig cfg = configRegistry.current();
        log.info("[REPORT-SCHED] Started. Báo cáo daily/weekly/monthly @ {}h VN, tick check 60s",
                cfg.scheduling().dailyReportHourVN());
    }

    public void stop() {
        executor.shutdownNow();
    }

    /** Public để chạy test thủ công nếu cần. */
    public void tick() {
        try {
            AppConfig cfg = configRegistry.current();
            int targetHour = cfg.scheduling().dailyReportHourVN();
            ZonedDateTime now = ZonedDateTime.now(VN_ZONE);
            if (now.getHour() != targetHour) return;
            LocalDate today = now.toLocalDate();
            if (today.equals(lastDailyFiredFor)) return; // đã fire trong giờ này

            fireDaily(today);
            if (today.getDayOfWeek() == DayOfWeek.MONDAY) fireWeekly(today);
            if (today.getDayOfMonth() == 1) fireMonthly(today);

            lastDailyFiredFor = today;
        } catch (Exception e) {
            log.error("[REPORT-SCHED] tick lỗi", e);
        }
    }

    private void fireDaily(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        emit(generator.generate(PeriodReport.Kind.DAILY, yesterday, yesterday),
                NotifyEvent.DAILY_REPORT);
    }

    private void fireWeekly(LocalDate today) {
        // Tuần trước: Monday(today-7) → Sunday(today-1)
        LocalDate weekStart = today.minusDays(7);
        LocalDate weekEnd = today.minusDays(1);
        emit(generator.generate(PeriodReport.Kind.WEEKLY, weekStart, weekEnd),
                NotifyEvent.WEEKLY_REPORT);
    }

    private void fireMonthly(LocalDate today) {
        // Tháng trước: ngày 1 đến ngày cuối của tháng trước
        LocalDate firstOfPrev = today.minusMonths(1).withDayOfMonth(1);
        LocalDate lastOfPrev = today.minusDays(1);
        emit(generator.generate(PeriodReport.Kind.MONTHLY, firstOfPrev, lastOfPrev),
                NotifyEvent.MONTHLY_REPORT);
    }

    private void emit(PeriodReport report, NotifyEvent event) {
        String formatted = ReportFormatter.format(report);
        log.info("[REPORT] === {} {} → {} ===\n{}",
                report.kind(), report.fromInclusive(), report.toInclusive(), formatted);
        try {
            generator.writeToFile(report, formatted);
        } catch (Exception e) {
            log.warn("[REPORT] Không lưu được file report: {}", e.getMessage());
        }
        notifier.send(event, formatted);
    }

    /** Helper thuần tính khoảng thời gian đến giờ báo cáo kế tiếp (debug). */
    public static Duration untilNextReport(int hourVN) {
        LocalDateTime now = LocalDateTime.now(VN_ZONE);
        LocalDateTime next = now.toLocalDate().atTime(LocalTime.of(hourVN, 0));
        if (!next.isAfter(now)) next = next.plusDays(1);
        return Duration.between(now, next);
    }
}
