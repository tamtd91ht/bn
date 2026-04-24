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
 * TTL ngắn (60s) để tick 15 phút không dùng data stale.
 */
public final class BarSeriesCache {

    private static final Logger log = LoggerFactory.getLogger(BarSeriesCache.class);
    private static final Duration TTL = Duration.ofSeconds(60);
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
        Entry cached = cache.get(key);
        if (cached != null && Duration.between(cached.loadedAt, Instant.now()).compareTo(TTL) < 0) {
            return cached.series;
        }
        long t0 = System.currentTimeMillis();
        BarSeries fresh = fetcher.fetch(symbol, interval, limit);
        cache.put(key, new Entry(fresh, Instant.now()));
        log.debug("[KLINES] fetch {} {} bars={} ({}ms)",
                symbol, interval, fresh.getBarCount(),
                System.currentTimeMillis() - t0);
        return fresh;
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
