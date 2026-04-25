package vn.tamtd.bot.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache {@link BarSeries} theo (symbol, interval). Tự refresh khi quá TTL.
 * TTL được tính dựa trên interval - TF lớn (1h, 4h) cache lâu hơn TF nhỏ (1m, 5m)
 * vì nến cuối cùng chỉ thay đổi đáng kể khi sang nến mới. Giúp fast-tick (60s)
 * không spam refetch klines vô ích.
 */
public final class BarSeriesCache {

    private static final Logger log = LoggerFactory.getLogger(BarSeriesCache.class);
    /** TTL fallback khi không parse được interval. */
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    private static final int DEFAULT_LIMIT = 200;

    private final KlineFetcher fetcher;
    private final Map<Key, Entry> cache = new ConcurrentHashMap<>();

    public BarSeriesCache(KlineFetcher fetcher) {
        this.fetcher = fetcher;
    }

    public BarSeries get(String symbol, String interval) {
        return get(symbol, interval, DEFAULT_LIMIT);
    }

    public BarSeries get(String symbol, String interval, int limit) {
        Key key = new Key(symbol, interval);
        Duration ttl = ttlFor(interval);
        Entry cached = cache.get(key);
        if (cached != null && Duration.between(cached.loadedAt, Instant.now()).compareTo(ttl) < 0) {
            return cached.series;
        }
        long t0 = System.currentTimeMillis();
        BarSeries fresh = fetcher.fetch(symbol, interval, limit);
        cache.put(key, new Entry(fresh, Instant.now()));
        log.debug("[KLINES] fetch {} {} bars={} ({}ms) ttl={}s",
                symbol, interval, fresh.getBarCount(),
                System.currentTimeMillis() - t0, ttl.getSeconds());
        return fresh;
    }

    /**
     * TTL = ~25% chiều dài 1 nến: đảm bảo đến cuối nến (close) sẽ refresh ít nhất 1 lần.
     * VD: 1h → 15min, 4h → 60min, 15m → ~4min, 1m → 60s (bảo thủ).
     */
    private static Duration ttlFor(String interval) {
        if (interval == null || interval.length() < 2) return DEFAULT_TTL;
        try {
            int n = Integer.parseInt(interval.substring(0, interval.length() - 1));
            char unit = interval.charAt(interval.length() - 1);
            long seconds = switch (unit) {
                case 'm' -> n * 60L;
                case 'h' -> n * 3600L;
                case 'd' -> n * 86400L;
                case 'w' -> n * 604800L;
                default -> 60L;
            };
            // Cap trên 1h, dưới 60s
            long ttl = Math.max(60L, Math.min(3600L, seconds / 4));
            return Duration.ofSeconds(ttl);
        } catch (NumberFormatException e) {
            return DEFAULT_TTL;
        }
    }

    public void invalidate(String symbol, String interval) {
        cache.remove(new Key(symbol, interval));
    }

    public void clear() {
        cache.clear();
    }

    private record Key(String symbol, String interval) {}
    private record Entry(BarSeries series, Instant loadedAt) {}
}
