package vn.tamtd.bot.storage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * State snapshot được persist trong {@code data/state.json}.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>Load lúc startup. Nếu missing/corrupt -> fallback sync từ Binance REST.</li>
 *   <li>Save sau MỖI event (entry / exit / kill-switch / rebalance / v0 snapshot / pause).</li>
 *   <li>Atomicity: viết vào file tạm rồi rename (xem {@link StateStore}).</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BotState {

    /** Vốn gốc tại lần snapshot gần nhất. Spot: USDT + giá trị coin. Futures: walletBalance + unrealizedPnL. */
    public double v0;

    public Instant v0SnapshotAt;

    /**
     * Spot: USDT đang trong quỹ dự phòng.
     * Futures: số margin USDT còn chưa dùng cho vị thế + có thể mở thêm.
     */
    public double reserveFund;

    public Map<String, Position> positions = new HashMap<>();

    public Map<String, Instant> cooldowns = new HashMap<>();
    public Map<String, Instant> alertCooldowns = new HashMap<>();

    /** Nếu != null: bot đang bị kill-switch pause đến thời điểm này. */
    public Instant killedUntil;

    /** Manual pause qua Telegram {@code /pause}. Không kicked bởi kill-switch. */
    public boolean paused;

    /** Số tick liên tiếp drawdown ≥ ngưỡng. Reset về 0 khi tick không lỗ. Khi đạt
     *  {@code risk.killSwitchHysteresisTicks} → fire kill-switch. */
    public int killSwitchTriggerCount;

    public BotState() {}

    public static BotState fresh() {
        BotState s = new BotState();
        s.v0 = 0;
        s.v0SnapshotAt = null;
        s.reserveFund = 0;
        s.paused = false;
        return s;
    }
}
