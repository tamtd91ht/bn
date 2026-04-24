package vn.tamtd.bot.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class CleanupJobTest {

    @Test
    void run_deletes_files_older_than_keepDays(@TempDir Path tmp) throws Exception {
        Path tradesDir = tmp.resolve("trades");
        Files.createDirectories(tradesDir);

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Path fresh = tradesDir.resolve(today.toString() + ".jsonl");
        Path oldFile = tradesDir.resolve(today.minusDays(40).toString() + ".jsonl");
        Path edgeFile = tradesDir.resolve(today.minusDays(30).toString() + ".jsonl");

        Files.writeString(fresh, "{}\n");
        Files.writeString(oldFile, "{}\n");
        Files.writeString(edgeFile, "{}\n");

        CleanupJob.runOnce(tmp, 30);

        assertThat(Files.exists(fresh)).isTrue();
        assertThat(Files.exists(oldFile)).isFalse();
        // Edge: file đúng ngày cutoff (today - keepDays) được giữ lại
        // vì logic là `fileDate.isBefore(cutoff)` - strictly older thì mới xoá
        assertThat(Files.exists(edgeFile)).isTrue();
    }

    @Test
    void run_ignores_files_with_bad_name(@TempDir Path tmp) throws Exception {
        Path tradesDir = tmp.resolve("trades");
        Files.createDirectories(tradesDir);
        Path weird = tradesDir.resolve("not-a-date.jsonl");
        Files.writeString(weird, "{}\n");
        CleanupJob.runOnce(tmp, 30);
        assertThat(Files.exists(weird)).isTrue();
    }
}
