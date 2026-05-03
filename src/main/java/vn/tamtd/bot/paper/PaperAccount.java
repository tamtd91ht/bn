package vn.tamtd.bot.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Số dư ảo cho chế độ paper-trade.
 *
 * <p>Lưu trên đĩa tại {@code data/paper_account.json} (atomic write giống StateStore).
 * Lần đầu tạo: số dư USDT = {@code initialUsdtBalance} từ config.
 *
 * <p>Mỗi lệnh paper "fill":
 * <ul>
 *   <li>BUY: trừ USDT (đã trừ fee), cộng base asset.</li>
 *   <li>SELL: trừ base asset, cộng USDT (đã trừ fee).</li>
 * </ul>
 *
 * <p>Thread-safe (mọi method mutate đều {@code synchronized}).
 */
public final class PaperAccount {

    private static final Logger log = LoggerFactory.getLogger(PaperAccount.class);

    private final Path file;
    private final Path tmp;
    private final ObjectMapper mapper;
    private final Object lock = new Object();

    private State state;

    public PaperAccount(Path dataDir, double initialUsdtBalance) {
        this.file = dataDir.resolve("paper_account.json");
        this.tmp = dataDir.resolve("paper_account.json.tmp");
        this.mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.state = load(initialUsdtBalance);
    }

    private State load(double initialUsdtBalance) {
        synchronized (lock) {
            if (Files.exists(file)) {
                try {
                    State s = mapper.readValue(file.toFile(), State.class);
                    if (s.balances == null) s.balances = new TreeMap<>();
                    log.info("[PAPER] Loaded paper account: {} assets, USDT={}",
                            s.balances.size(), s.balances.getOrDefault("USDT", BigDecimal.ZERO).toPlainString());
                    return s;
                } catch (IOException e) {
                    log.error("[PAPER] Đọc paper_account.json lỗi, init lại từ initialUsdtBalance={}",
                            initialUsdtBalance, e);
                }
            }
            State s = new State();
            s.createdAt = Instant.now();
            s.balances = new TreeMap<>();
            s.balances.put("USDT", BigDecimal.valueOf(initialUsdtBalance));
            log.info("[PAPER] Khởi tạo paper account mới: USDT={}", initialUsdtBalance);
            persistLocked(s);
            return s;
        }
    }

    private void persistLocked(State s) {
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(tmp.toFile(), s);
            Files.move(tmp, file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("[PAPER] Save paper_account.json lỗi: {}", file, e);
        }
    }

    /** Trả số dư free của 1 asset ("USDT", "BTC"...). 0 nếu chưa có. */
    public BigDecimal free(String asset) {
        synchronized (lock) {
            return state.balances.getOrDefault(asset, BigDecimal.ZERO);
        }
    }

    /** Snapshot read-only của tất cả balances (asset → amount). */
    public Map<String, BigDecimal> snapshot() {
        synchronized (lock) {
            return new LinkedHashMap<>(state.balances);
        }
    }

    /** Trừ USDT, cộng base asset (BUY paper fill). qtyBase đã trừ fee. */
    public synchronized void applyBuy(String baseAsset, BigDecimal usdtSpent, BigDecimal qtyBaseAfterFee) {
        BigDecimal usdt = state.balances.getOrDefault("USDT", BigDecimal.ZERO).subtract(usdtSpent);
        if (usdt.signum() < 0) {
            log.warn("[PAPER] Buy gây USDT âm: {} → cap về 0", usdt.toPlainString());
            usdt = BigDecimal.ZERO;
        }
        state.balances.put("USDT", usdt);
        BigDecimal base = state.balances.getOrDefault(baseAsset, BigDecimal.ZERO).add(qtyBaseAfterFee);
        state.balances.put(baseAsset, base);
        state.updatedAt = Instant.now();
        persistLocked(state);
    }

    /** Trừ base asset, cộng USDT (SELL paper fill). usdtReceived đã trừ fee. */
    public synchronized void applySell(String baseAsset, BigDecimal qtyBaseSold, BigDecimal usdtReceivedAfterFee) {
        BigDecimal base = state.balances.getOrDefault(baseAsset, BigDecimal.ZERO).subtract(qtyBaseSold);
        if (base.signum() < 0) {
            log.warn("[PAPER] Sell gây {} âm: {} → cap về 0",
                    baseAsset, base.toPlainString());
            base = BigDecimal.ZERO;
        }
        if (base.signum() == 0) {
            state.balances.remove(baseAsset);
        } else {
            state.balances.put(baseAsset, base);
        }
        BigDecimal usdt = state.balances.getOrDefault("USDT", BigDecimal.ZERO).add(usdtReceivedAfterFee);
        state.balances.put("USDT", usdt);
        state.updatedAt = Instant.now();
        persistLocked(state);
    }

    /** Container persisted - public để Jackson de/serialize. */
    public static final class State {
        public Instant createdAt;
        public Instant updatedAt;
        public Map<String, BigDecimal> balances = new TreeMap<>();
    }
}
