package vn.tamtd.bot.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Append-only JSONL writer. Mỗi bucket là 1 thư mục trong {@code data/},
 * file tự rotate theo ngày ({@code yyyy-MM-dd.jsonl}).
 *
 * <p>Ghi an toàn dưới multi-thread bằng {@code synchronized}, flush sau mỗi record
 * để không mất data khi crash. Đổi lại perf không tối ưu, nhưng volume 1 bot nhỏ
 * (~vài record/phút) nên không đáng kể.
 */
public final class JsonlWriter {

    private static final Logger log = LoggerFactory.getLogger(JsonlWriter.class);
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public enum Bucket {
        TRADES("trades"),
        ORDERS("orders"),
        SIGNALS("signals"),
        EQUITY("equity");

        final String dir;
        Bucket(String dir) { this.dir = dir; }
    }

    private final Path dataDir;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    public JsonlWriter(Path dataDir) {
        this.dataDir = dataDir;
        this.mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Append 1 record JSON vào file {@code data/<bucket>/YYYY-MM-DD.jsonl}.
     * Lỗi IO được log nhưng không throw - không muốn stop bot vì write log fail.
     */
    public void append(Bucket bucket, Object record) {
        synchronized (lock) {
            try {
                Path file = resolveFile(bucket);
                Files.createDirectories(file.getParent());
                String line = mapper.writeValueAsString(record) + "\n";
                try (BufferedWriter w = Files.newBufferedWriter(file,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND)) {
                    w.write(line);
                    w.flush();
                }
            } catch (JsonProcessingException e) {
                log.error("Serialize JSONL record lỗi bucket={}", bucket, e);
            } catch (IOException e) {
                log.error("Ghi JSONL lỗi bucket={}", bucket, e);
            }
        }
    }

    private Path resolveFile(Bucket bucket) {
        String date = LocalDate.now(VN_ZONE).toString();
        return dataDir.resolve(bucket.dir).resolve(date + ".jsonl");
    }
}
