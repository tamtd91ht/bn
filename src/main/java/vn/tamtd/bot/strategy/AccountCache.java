package vn.tamtd.bot.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * TTL cache mỏng bọc {@link CapitalInitializer#fetchAccountSnapshot()} để không spam
 * endpoint {@code /api/v3/account} khi EntryPlanner + nhiều lệnh close trong cùng 1 tick
 * đều cần check balance thật từ Binance.
 *
 * <p>Gọi {@link #invalidate()} sau mỗi lệnh BUY/SELL được fill để lần đọc kế tiếp
 * nhận balance cập nhật (free USDT/asset thay đổi ngay sau lệnh).
 *
 * <p>Thread-safe (các method public đều {@code synchronized}).
 */
public final class AccountCache {

    private static final Logger log = LoggerFactory.getLogger(AccountCache.class);
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final CapitalInitializer capitalInitializer;
    private final Duration ttl;
    private CapitalInitializer.AccountSnapshot cached;
    private Instant cachedAt = Instant.EPOCH;

    public AccountCache(CapitalInitializer capitalInitializer) {
        this(capitalInitializer, DEFAULT_TTL);
    }

    public AccountCache(CapitalInitializer capitalInitializer, Duration ttl) {
        this.capitalInitializer = capitalInitializer;
        this.ttl = ttl;
    }

    /** Lấy snapshot (cached nếu còn hạn TTL, ngược lại fetch mới). */
    public synchronized CapitalInitializer.AccountSnapshot snapshot() throws Exception {
        if (cached == null || Duration.between(cachedAt, Instant.now()).compareTo(ttl) > 0) {
            cached = capitalInitializer.fetchAccountSnapshot();
            cachedAt = Instant.now();
        }
        return cached;
    }

    /** Invalidate sau khi vừa có lệnh fill để lần đọc kế tiếp chắc chắn fresh. */
    public synchronized void invalidate() {
        cached = null;
        cachedAt = Instant.EPOCH;
    }

    /** Số USDT free (có thể dùng ngay cho lệnh mới). Trả {@link BigDecimal#ZERO} nếu lỗi. */
    public BigDecimal freeUsdt() {
        return freeAsset("USDT");
    }

    /**
     * Số free của 1 asset bất kỳ (VD "KAT" cho KATUSDT). Không tính phần locked
     * (Binance trừ locked riêng cho order đang mở).
     * Trả {@link BigDecimal#ZERO} nếu asset không có hoặc fetch lỗi.
     */
    public BigDecimal freeAsset(String asset) {
        try {
            for (var h : snapshot().holdings()) {
                if (asset.equals(h.asset())) {
                    return BigDecimal.valueOf(h.free());
                }
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            log.warn("[ACCOUNT-CACHE] Fetch balance lỗi ({}) - trả 0 cho asset {}",
                    e.getMessage(), asset);
            return BigDecimal.ZERO;
        }
    }
}
