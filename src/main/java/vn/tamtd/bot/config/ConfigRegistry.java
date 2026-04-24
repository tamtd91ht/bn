package vn.tamtd.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Giữ snapshot {@link AppConfig} hiện hành + phát sự kiện khi reload.
 *
 * <p><b>Pattern:</b> code đọc config qua {@link #current()}, mỗi lần đọc nhận 1 snapshot immutable.
 * Khi file YAML thay đổi, {@link ConfigFileWatcher} gọi {@link #reload()} - validate OK thì swap
 * snapshot mới + gọi listeners; fail thì giữ snapshot cũ và log WARN (fail-soft).
 *
 * <p>Code đang chạy 1 tick nên chụp snapshot ở đầu tick và dùng xuyên suốt để tránh race
 * với reload giữa tick.
 */
public final class ConfigRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConfigRegistry.class);

    private final Path baseDir;
    private final AtomicReference<AppConfig> currentRef = new AtomicReference<>();
    private final List<Consumer<AppConfig>> listeners = new CopyOnWriteArrayList<>();

    public ConfigRegistry(Path baseDir, AppConfig initial) {
        this.baseDir = baseDir;
        this.currentRef.set(initial);
    }

    public static ConfigRegistry bootstrap(Path baseDir) throws IOException {
        AppConfig initial = ConfigLoader.load(baseDir);
        return new ConfigRegistry(baseDir, initial);
    }

    public AppConfig current() {
        return currentRef.get();
    }

    public Path baseDir() {
        return baseDir;
    }

    public void addListener(Consumer<AppConfig> listener) {
        listeners.add(listener);
    }

    /**
     * Force reload từ file. Fail-soft: lỗi parse/validate giữ nguyên snapshot cũ.
     * @return true nếu reload thành công và có thay đổi
     */
    public synchronized boolean reload() {
        AppConfig previous = currentRef.get();
        AppConfig fresh;
        try {
            fresh = ConfigLoader.load(baseDir);
        } catch (Exception e) {
            log.warn("[CONFIG:RELOAD_FAIL] Giữ snapshot cũ. Reason: {}", e.getMessage());
            return false;
        }
        if (fresh.equals(previous)) {
            log.info("[CONFIG:RELOAD] File thay đổi nhưng nội dung không khác - skip notify listeners");
            return false;
        }
        if (!canSwap(previous, fresh)) {
            log.warn("[CONFIG:RELOAD_REJECTED] Field không reload-able thay đổi (mode/leverage khi có position)."
                    + " Restart bot để áp dụng. Giữ snapshot cũ.");
            return false;
        }
        currentRef.set(fresh);
        log.info("[CONFIG:RELOADED] OK. mode={} lev={} testnet={} watchlist={} overrides={}",
                fresh.exchange().mode(), fresh.exchange().leverage(),
                fresh.exchange().useTestnet(),
                fresh.watchlist().symbols(),
                fresh.symbols().keySet());
        List<Exception> notifyErrors = new ArrayList<>();
        for (Consumer<AppConfig> l : listeners) {
            try { l.accept(fresh); } catch (Exception e) { notifyErrors.add(e); }
        }
        if (!notifyErrors.isEmpty()) {
            log.warn("[CONFIG:RELOAD] {} listener lỗi khi nhận config mới", notifyErrors.size());
        }
        return true;
    }

    /**
     * Một số field không reload-able khi bot đã chạy có vị thế - buộc restart.
     * Hiện tại: mode và useTestnet. (leverage không đổi runtime được nếu đã có order đang mở,
     * nhưng chúng tôi chấp nhận đổi - sẽ apply cho lệnh mới.)
     */
    private boolean canSwap(AppConfig prev, AppConfig fresh) {
        if (!prev.exchange().mode().equalsIgnoreCase(fresh.exchange().mode())) return false;
        if (prev.exchange().useTestnet() != fresh.exchange().useTestnet()) return false;
        return true;
    }
}
