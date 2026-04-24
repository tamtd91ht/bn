package vn.tamtd.bot.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.config.ExchangeMode;
import vn.tamtd.bot.exchange.ExchangeClient;
import vn.tamtd.bot.storage.BotState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot vốn gốc {@code v0} = tổng USDT-value của tài khoản.
 * Hoạt động cho:
 * <ul>
 *   <li>SPOT: duyệt balances[] quy về USDT qua latestPrice.</li>
 *   <li>FUTURES: dùng totalWalletBalance + totalUnrealizedProfit từ /fapi/v2/account.</li>
 * </ul>
 */
public final class CapitalInitializer {

    private static final Logger log = LoggerFactory.getLogger(CapitalInitializer.class);
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConfigRegistry configRegistry;
    private final ExchangeClient client;

    public CapitalInitializer(ConfigRegistry configRegistry, ExchangeClient client) {
        this.configRegistry = configRegistry;
        this.client = client;
    }

    public boolean snapshotIfNeeded(BotState state) {
        AppConfig config = configRegistry.current();
        boolean firstTime = state.v0 <= 0 || state.v0SnapshotAt == null;
        boolean dueRefresh = !firstTime && isDue(state, config) && state.positions.isEmpty();
        // Mode mismatch: user đổi từ mixed (có watchlist) sang scanner-only (watchlist rỗng)
        // → reserveFund cũ chỉ có 20% v0, cần re-snapshot để dùng full vốn
        boolean watchlistEmpty = config.watchlist().symbols().isEmpty();
        boolean modeMismatch = !firstTime && state.positions.isEmpty() && watchlistEmpty
                && state.v0 > 0 && state.reserveFund < state.v0 * 0.99;
        if (!firstTime && !dueRefresh && !modeMismatch) return false;

        String trigger = firstTime ? "FIRST_TIME" : (modeMismatch ? "MODE_MISMATCH" : "DUE_REFRESH");
        log.info("[CAPITAL] Snapshot v0 (trigger={}), v0 cũ={}",
                trigger, String.format("%.4f", state.v0));
        try {
            double equity = computeTotalEquity();
            // Scanner-only mode (watchlist rỗng): gộp reserve + active vào 1 pool
            // → scanner được phép dùng toàn bộ vốn, không bị giới hạn ở 20% reserve
            double reserve = watchlistEmpty ? equity : equity * config.capital().reservePct();
            double activeCapital = equity - reserve;
            double oldV0 = state.v0;
            state.v0 = equity;
            state.reserveFund = reserve;
            state.v0SnapshotAt = Instant.now();
            double deltaPct = oldV0 > 0 ? (equity - oldV0) / oldV0 * 100.0 : 0.0;
            log.info("[CAPITAL] v0={} reserveFund={} ({}%) activeCapital={} delta={}%",
                    String.format("%.4f", equity),
                    String.format("%.4f", reserve),
                    String.format("%.1f", config.capital().reservePct() * 100),
                    String.format("%.4f", activeCapital),
                    oldV0 > 0 ? String.format("%+.2f", deltaPct) : "n/a");
            return true;
        } catch (Exception e) {
            log.error("[CAPITAL] Snapshot v0 LỖI: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean isDue(BotState state, AppConfig config) {
        int hours = config.capital().v0SnapshotEveryHours();
        if (hours <= 0) return false;
        return Duration.between(state.v0SnapshotAt, Instant.now()).toHours() >= hours
                && !LocalDate.now(VN_ZONE).equals(
                        state.v0SnapshotAt.atZone(VN_ZONE).toLocalDate());
    }

    public double computeTotalEquity() throws Exception {
        return fetchAccountSnapshot().totalEquityUsdt();
    }

    public AccountSnapshot fetchAccountSnapshot() throws Exception {
        ExchangeMode mode = configRegistry.current().mode();
        return mode.isFutures() ? fetchFuturesSnapshot() : fetchSpotSnapshot();
    }

    private AccountSnapshot fetchSpotSnapshot() throws Exception {
        String json = client.rawAccount();
        JsonNode root = MAPPER.readTree(json);
        double total = 0.0;
        int skipCount = 0;
        List<AssetHolding> holdings = new ArrayList<>();
        for (JsonNode bal : root.get("balances")) {
            String asset = bal.get("asset").asText();
            double free = bal.get("free").asDouble();
            double locked = bal.get("locked").asDouble();
            double amount = free + locked;
            if (amount == 0) continue;

            if ("USDT".equals(asset)) {
                total += amount;
                holdings.add(new AssetHolding(asset, free, locked, amount, amount,
                        AssetSource.NATIVE, null));
                continue;
            }
            if (isStablecoin(asset)) {
                total += amount;
                holdings.add(new AssetHolding(asset, free, locked, amount, amount,
                        AssetSource.STABLE, null));
                continue;
            }
            String symbol = asset + "USDT";
            try {
                BigDecimal price = client.latestPrice(symbol);
                double value = new BigDecimal(amount).multiply(price)
                        .setScale(8, RoundingMode.HALF_UP).doubleValue();
                total += value;
                holdings.add(new AssetHolding(asset, free, locked, amount, value,
                        AssetSource.PRICED, price));
            } catch (Exception e) {
                skipCount++;
                holdings.add(new AssetHolding(asset, free, locked, amount, 0.0,
                        AssetSource.SKIPPED, null));
            }
        }
        log.info("[CAPITAL] Spot equity = {} USDT từ {} asset (skipped={})",
                String.format("%.4f", total), holdings.size() - skipCount, skipCount);
        return new AccountSnapshot(total, List.copyOf(holdings));
    }

    private AccountSnapshot fetchFuturesSnapshot() throws Exception {
        String json = client.rawAccount();
        JsonNode root = MAPPER.readTree(json);
        double totalWalletBalance = root.path("totalWalletBalance").asDouble();
        double totalUnrealizedProfit = root.path("totalUnrealizedProfit").asDouble();
        double equity = totalWalletBalance + totalUnrealizedProfit;
        log.info("[CAPITAL] Futures equity = {} (wallet={} + unrealized={})",
                String.format("%.4f", equity),
                String.format("%.4f", totalWalletBalance),
                String.format("%.4f", totalUnrealizedProfit));
        List<AssetHolding> holdings = new ArrayList<>();
        holdings.add(new AssetHolding("USDT", totalWalletBalance, 0,
                totalWalletBalance, totalWalletBalance, AssetSource.NATIVE, null));
        if (totalUnrealizedProfit != 0) {
            holdings.add(new AssetHolding("UNREALIZED_PNL", 0, 0, 0,
                    totalUnrealizedProfit, AssetSource.PRICED, null));
        }
        return new AccountSnapshot(equity, List.copyOf(holdings));
    }

    public enum AssetSource { NATIVE, STABLE, PRICED, SKIPPED }

    public record AssetHolding(
            String asset, double free, double locked, double amount,
            double usdtValue, AssetSource source, BigDecimal priceUsdt
    ) {}

    public record AccountSnapshot(double totalEquityUsdt, List<AssetHolding> holdings) {}

    private static boolean isStablecoin(String asset) {
        return switch (asset) {
            case "USDC", "FDUSD", "TUSD", "BUSD", "DAI", "USDP", "USDD", "RLUSD", "USD1" -> true;
            default -> false;
        };
    }
}
