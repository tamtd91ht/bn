package vn.tamtd.bot.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigLoader;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.exchange.ExchangeClient;
import vn.tamtd.bot.exchange.ExchangeFactory;
import vn.tamtd.bot.exchange.RateLimiter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Verify API key + print balance. Hoạt động cho cả spot và futures theo config.
 */
@CommandLine.Command(
        name = "account",
        description = "Verify API key + print balance (spot hoặc futures)"
)
public final class AccountCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(AccountCommand.class);

    @CommandLine.Option(names = {"-c", "--config-dir"},
            description = "Thư mục chứa app.yml (mặc định: thư mục jar)")
    Path configDir;

    @Override
    public Integer call() throws Exception {
        Path baseDir = configDir != null ? configDir : ConfigLoader.jarDir();
        ConfigRegistry registry = ConfigRegistry.bootstrap(baseDir);
        AppConfig config = registry.current();
        config.validateSecretsForTrading();

        ExchangeFactory.Pair pair = ExchangeFactory.create(config, new RateLimiter());
        ExchangeClient client = pair.client();
        ObjectMapper mapper = new ObjectMapper();

        String accountJson;
        try {
            accountJson = client.rawAccount();
        } catch (Exception e) {
            System.err.println("ERROR: không gọi được account API");
            System.err.println("Kiểm tra: BINANCE_API_KEY/SECRET đúng, IP whitelist, đồng hồ NTP, quyền key.");
            System.err.println("Chi tiết: " + e.getMessage());
            return 1;
        }
        System.out.println("=== API OK (mode=" + config.mode() + ") ===");

        JsonNode root = mapper.readTree(accountJson);
        if (config.mode().isFutures()) {
            printFutures(root, config);
        } else {
            printSpot(root, client, config);
        }
        return 0;
    }

    private void printSpot(JsonNode root, ExchangeClient client, AppConfig config) throws Exception {
        System.out.println("accountType: " + root.path("accountType").asText());
        System.out.println("canTrade:    " + root.path("canTrade").asBoolean());
        System.out.println();

        record Row(String asset, BigDecimal free, BigDecimal locked, BigDecimal usdtValue) {}
        List<Row> rows = new ArrayList<>();
        double totalUsdt = 0;

        for (JsonNode bal : root.get("balances")) {
            String asset = bal.get("asset").asText();
            BigDecimal free = new BigDecimal(bal.get("free").asText());
            BigDecimal locked = new BigDecimal(bal.get("locked").asText());
            BigDecimal amount = free.add(locked);
            if (amount.signum() <= 0) continue;

            BigDecimal usdtValue;
            if ("USDT".equals(asset)) {
                usdtValue = amount;
            } else {
                try {
                    BigDecimal price = client.latestPrice(asset + "USDT");
                    usdtValue = amount.multiply(price).setScale(2, java.math.RoundingMode.HALF_UP);
                } catch (Exception e) {
                    usdtValue = null;
                }
            }
            rows.add(new Row(asset, free, locked, usdtValue));
            if (usdtValue != null) totalUsdt += usdtValue.doubleValue();
        }
        rows.sort(Comparator.<Row, BigDecimal>comparing(
                r -> r.usdtValue() == null ? BigDecimal.ZERO : r.usdtValue()).reversed());

        System.out.printf("%-10s %-18s %-18s %-14s%n", "ASSET", "FREE", "LOCKED", "≈ USDT");
        System.out.println("-".repeat(62));
        for (Row r : rows) {
            System.out.printf("%-10s %-18s %-18s %-14s%n",
                    r.asset(), r.free().toPlainString(), r.locked().toPlainString(),
                    r.usdtValue() == null ? "(n/a)" : r.usdtValue().toPlainString());
        }
        System.out.println("-".repeat(62));
        System.out.printf("TOTAL ≈ %.2f USDT%n", totalUsdt);
    }

    private void printFutures(JsonNode root, AppConfig config) {
        System.out.println("totalWalletBalance:    " + root.path("totalWalletBalance").asText());
        System.out.println("totalUnrealizedProfit: " + root.path("totalUnrealizedProfit").asText());
        System.out.println("totalMarginBalance:    " + root.path("totalMarginBalance").asText());
        System.out.println("availableBalance:      " + root.path("availableBalance").asText());
        System.out.println("canTrade:              " + root.path("canTrade").asBoolean());
        System.out.println();
        System.out.println("=== Positions đang mở ===");
        int count = 0;
        for (JsonNode p : root.path("positions")) {
            double amt = p.path("positionAmt").asDouble();
            if (amt == 0) continue;
            count++;
            System.out.printf("  %s positionAmt=%s entry=%s leverage=%s isolated=%s unrealizedProfit=%s%n",
                    p.path("symbol").asText(),
                    p.path("positionAmt").asText(),
                    p.path("entryPrice").asText(),
                    p.path("leverage").asText(),
                    p.path("isolated").asBoolean(),
                    p.path("unrealizedProfit").asText());
        }
        if (count == 0) System.out.println("  (không có position)");
    }
}
