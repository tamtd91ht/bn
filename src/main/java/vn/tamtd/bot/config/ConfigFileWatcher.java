package vn.tamtd.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Theo dõi file {@code app.yml} / {@code symbols.yml} trong {@code baseDir}.
 * Khi có event MODIFY/CREATE → debounce {@link AppConfig.Dynamic#reloadDebounceMs()}ms
 * rồi gọi {@link ConfigRegistry#reload()}.
 *
 * <p>Debounce cần thiết vì editor thường ghi file qua tmp+rename → 2-3 event liên tiếp.
 *
 * <p>Watcher chạy daemon thread riêng, stop an toàn qua {@link #stop()}.
 */
public final class ConfigFileWatcher {

    private static final Logger log = LoggerFactory.getLogger(ConfigFileWatcher.class);

    private final ConfigRegistry registry;
    private final Path dir;
    private final long debounceMs;

    private Thread watcherThread;
    private WatchService watchService;
    private volatile boolean running;

    private final ScheduledExecutorService debounceScheduler;
    private volatile ScheduledFuture<?> pendingReload;

    public ConfigFileWatcher(ConfigRegistry registry) {
        this.registry = registry;
        this.dir = registry.baseDir();
        this.debounceMs = Math.max(200, registry.current().dynamic().reloadDebounceMs());
        this.debounceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "config-reload");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!registry.current().dynamic().hotReload()) {
            log.info("[CONFIG] hotReload=false → không start file watcher");
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException e) {
            log.error("[CONFIG] Register WatchService lỗi - tắt hot-reload", e);
            return;
        }
        running = true;
        watcherThread = new Thread(this::loop, "config-file-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        log.info("[CONFIG] FileWatcher started: dir={}, debounce={}ms", dir, debounceMs);
    }

    public void stop() {
        running = false;
        if (watcherThread != null) watcherThread.interrupt();
        if (watchService != null) {
            try { watchService.close(); } catch (IOException ignored) {}
        }
        debounceScheduler.shutdownNow();
    }

    private void loop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            boolean trigger = false;
            for (WatchEvent<?> ev : key.pollEvents()) {
                Object ctx = ev.context();
                if (!(ctx instanceof Path p)) continue;
                String name = p.getFileName().toString();
                if (ConfigLoader.APP_YAML.equals(name) || ConfigLoader.SYMBOLS_YAML.equals(name)) {
                    trigger = true;
                    log.debug("[CONFIG] File event {} {}", ev.kind().name(), name);
                }
            }
            if (trigger) scheduleReload();
            if (!key.reset()) {
                log.warn("[CONFIG] WatchKey invalid - dừng watcher");
                return;
            }
        }
    }

    private synchronized void scheduleReload() {
        if (pendingReload != null && !pendingReload.isDone()) {
            pendingReload.cancel(false);
        }
        pendingReload = debounceScheduler.schedule(
                () -> {
                    log.info("[CONFIG] Debounce xong → thử reload");
                    registry.reload();
                },
                debounceMs, TimeUnit.MILLISECONDS);
    }
}
