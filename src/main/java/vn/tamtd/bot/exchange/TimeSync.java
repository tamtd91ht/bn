package vn.tamtd.bot.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sync clock skew với Binance server. Dùng chung cho Spot và Futures - gọi serverTime
 * endpoint tương ứng của exchange client được inject.
 */
public final class TimeSync {

    private static final Logger log = LoggerFactory.getLogger(TimeSync.class);

    private final ExchangeClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile long offsetMs = 0;
    private ScheduledExecutorService scheduler;

    public TimeSync(ExchangeClient client) {
        this.client = client;
    }

    public long currentTimeMillis() {
        return System.currentTimeMillis() + offsetMs;
    }

    public long offsetMs() {
        return offsetMs;
    }

    public void start() {
        syncOnce();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "time-sync");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::syncOnce, 5, 5, TimeUnit.MINUTES);
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    public void syncOnce() {
        try {
            long localBefore = System.currentTimeMillis();
            String json = client.rawServerTime();
            long localAfter = System.currentTimeMillis();
            JsonNode root = mapper.readTree(json);
            long serverTime = root.get("serverTime").asLong();
            long localMid = (localBefore + localAfter) / 2;
            long newOffset = serverTime - localMid;
            long delta = Math.abs(newOffset - offsetMs);
            offsetMs = newOffset;
            if (delta > 1000) {
                log.warn("[TIME-SYNC] Clock skew lớn: offset={}ms (delta={}ms)",
                        offsetMs, delta);
            } else {
                log.info("[TIME-SYNC] offset={}ms delta={}ms", offsetMs, delta);
            }
        } catch (Exception e) {
            log.error("TimeSync lỗi", e);
        }
    }
}
