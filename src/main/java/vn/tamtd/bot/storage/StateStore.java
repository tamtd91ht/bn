package vn.tamtd.bot.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Load/save {@link BotState} sang {@code data/state.json}.
 * Ghi atomic: viết file tmp rồi rename, tránh crash giữa chừng làm file rỗng/rách.
 */
public final class StateStore {

    private static final Logger log = LoggerFactory.getLogger(StateStore.class);

    private final Path stateFile;
    private final Path tmpFile;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    public StateStore(Path dataDir) {
        this.stateFile = dataDir.resolve("state.json");
        this.tmpFile = dataDir.resolve("state.json.tmp");
        this.mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Load state từ disk. Trả về {@link BotState#fresh()} nếu file chưa tồn tại
     * hoặc không đọc được (caller nên sync lại từ Binance REST).
     */
    public BotState load() {
        synchronized (lock) {
            if (!Files.exists(stateFile)) {
                log.info("state.json chưa tồn tại, khởi tạo state mới");
                return BotState.fresh();
            }
            try {
                return mapper.readValue(stateFile.toFile(), BotState.class);
            } catch (IOException e) {
                log.error("Đọc state.json lỗi, sẽ khởi tạo fresh state. File có thể corrupt: {}",
                        stateFile, e);
                return BotState.fresh();
            }
        }
    }

    /**
     * Save state atomic: viết vào state.json.tmp rồi rename sang state.json.
     */
    public void save(BotState state) {
        synchronized (lock) {
            try {
                Files.createDirectories(stateFile.getParent());
                mapper.writeValue(tmpFile.toFile(), state);
                Files.move(tmpFile, stateFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.error("Không save được state.json: {}", stateFile, e);
            }
        }
    }
}
