package vn.tamtd.bot.exchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token bucket rate limiter cho Binance Spot.
 * <ul>
 *   <li>Weight: 1200 weight/phút (REST common)</li>
 *   <li>Orders: 10 orders/giây</li>
 * </ul>
 *
 * <p>Dùng trước mỗi request REST để block nếu hết token.
 * Nếu token không đủ: sleep đến khi đủ, rồi consume.
 */
public final class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final Bucket weightBucket;
    private final Bucket orderBucket;

    public RateLimiter() {
        // 1200 weight / 60000 ms => refill 20 per second
        this.weightBucket = new Bucket(1200, 1200, 60_000);
        // 10 orders / 1000 ms
        this.orderBucket = new Bucket(10, 10, 1000);
    }

    /** Chờ đến khi có đủ weight, rồi consume. */
    public void acquireWeight(int cost) {
        weightBucket.acquire(cost);
    }

    /** Chờ đến khi có đủ order slot, rồi consume 1. */
    public void acquireOrder() {
        orderBucket.acquire(1);
    }

    /**
     * Khi gặp HTTP 429 / 418 từ Binance, gọi để giảm token về 0 và đợi theo Retry-After.
     */
    public void penaltyWait(long retryAfterMs) {
        log.warn("Rate limit hit, đợi {}ms theo Retry-After", retryAfterMs);
        weightBucket.drainAndBlock(retryAfterMs);
        orderBucket.drainAndBlock(retryAfterMs);
    }

    private static final class Bucket {
        private final long capacity;
        private final long windowMs;
        private double tokens;
        private long lastRefillMs;

        Bucket(long initialTokens, long capacity, long windowMs) {
            this.capacity = capacity;
            this.windowMs = windowMs;
            this.tokens = initialTokens;
            this.lastRefillMs = System.currentTimeMillis();
        }

        synchronized void acquire(int cost) {
            while (true) {
                refill();
                if (tokens >= cost) {
                    tokens -= cost;
                    return;
                }
                long waitMs = (long) Math.ceil((cost - tokens) * windowMs / (double) capacity);
                try {
                    wait(Math.max(10, waitMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted khi đợi rate limit", e);
                }
            }
        }

        synchronized void drainAndBlock(long durationMs) {
            tokens = 0;
            long until = System.currentTimeMillis() + durationMs;
            while (System.currentTimeMillis() < until) {
                try {
                    wait(until - System.currentTimeMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            lastRefillMs = System.currentTimeMillis();
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillMs;
            if (elapsed <= 0) return;
            double refill = (double) capacity * elapsed / windowMs;
            tokens = Math.min(capacity, tokens + refill);
            lastRefillMs = now;
        }
    }
}
