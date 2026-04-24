package vn.tamtd.bot.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Scheduled job xoá JSONL cũ hơn {@code keepDays} ngày.
 * Chạy mỗi ngày vào giờ VN cấu hình ({@code scheduling.cleanupHourVN}).
 */
public final class CleanupJob {

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final Path dataDir;
    private final int keepDays;
    private final int cleanupHourVN;
    private ScheduledExecutorService scheduler;

    public CleanupJob(Path dataDir, int keepDays, int cleanupHourVN) {
        this.dataDir = dataDir;
        this.keepDays = keepDays;
        this.cleanupHourVN = cleanupHourVN;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cleanup-job");
            t.setDaemon(true);
            return t;
        });
        long initialDelay = computeInitialDelaySeconds();
        long periodSeconds = TimeUnit.DAYS.toSeconds(1);
        scheduler.scheduleAtFixedRate(this::safeRun, initialDelay, periodSeconds, TimeUnit.SECONDS);
        log.info("CleanupJob scheduled: cleanupHourVN={}h, keepDays={}, firstRunIn={}s",
                cleanupHourVN, keepDays, initialDelay);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private long computeInitialDelaySeconds() {
        LocalDateTime now = LocalDateTime.now(VN_ZONE);
        LocalDateTime nextRun = now.toLocalDate().atTime(LocalTime.of(cleanupHourVN, 0));
        if (!nextRun.isAfter(now)) {
            nextRun = nextRun.plusDays(1);
        }
        return Duration.between(now, nextRun).toSeconds();
    }

    private void safeRun() {
        try {
            run();
        } catch (Exception e) {
            log.error("CleanupJob tick lỗi", e);
        }
    }

    /** Public để test có thể gọi trực tiếp. */
    public void run() {
        LocalDate cutoff = LocalDate.now(VN_ZONE).minusDays(keepDays);
        int deleted = 0;
        for (JsonlWriter.Bucket bucket : JsonlWriter.Bucket.values()) {
            Path bucketDir = dataDir.resolve(bucket.dir);
            if (!Files.exists(bucketDir)) continue;
            try (Stream<Path> files = Files.list(bucketDir)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".jsonl")) continue;
                    String dateStr = name.substring(0, name.length() - ".jsonl".length());
                    try {
                        LocalDate fileDate = LocalDate.parse(dateStr);
                        if (fileDate.isBefore(cutoff)) {
                            Files.deleteIfExists(p);
                            deleted++;
                            log.info("Xoá file JSONL cũ: {}", p);
                        }
                    } catch (Exception parseErr) {
                        log.warn("Tên file không đúng format yyyy-MM-dd.jsonl, bỏ qua: {}", p);
                    }
                }
            } catch (IOException e) {
                log.error("List bucket dir lỗi: {}", bucketDir, e);
            }
        }
        log.info("CleanupJob done: xoá {} file (keepDays={})", deleted, keepDays);

        // Cảnh báo nếu thư mục data lớn hơn 500MB
        long totalSize = sizeOf(dataDir);
        if (totalSize > 500L * 1024L * 1024L) {
            log.warn("Thư mục data/ đang {}MB - vượt 500MB, kiểm tra retention/config",
                    totalSize / 1024 / 1024);
        }
    }

    private long sizeOf(Path dir) {
        if (!Files.exists(dir)) return 0;
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0; }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Dùng cho unit test / admin tool. */
    public static void runOnce(Path dataDir, int keepDays) {
        new CleanupJob(dataDir, keepDays, 0).run();
    }
}
