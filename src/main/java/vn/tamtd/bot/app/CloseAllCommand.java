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
import vn.tamtd.bot.exchange.FilterCache;
import vn.tamtd.bot.exchange.FilterUtil;
import vn.tamtd.bot.exchange.OrderGateway;
import vn.tamtd.bot.exchange.RateLimiter;
import vn.tamtd.bot.exchange.SymbolFilter;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Emergency close:
 * <ul>
 *   <li>SPOT: bán tất cả non-USDT asset về USDT.</li>
 *   <li>FUTURES: đóng tất cả vị thế đang mở (market reduceOnly).</li>
 * </ul>
 */
@CommandLine.Command(
        name = "close-all",
        description = "KHẨN CẤP: đóng tất cả vị thế / bán tất cả coin về USDT"
)
public final class CloseAllCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(CloseAllCommand.class);

    @CommandLine.Option(names = {"-c", "--config-dir"},
            description = "Thư mục config (mặc định: thư mục jar)")
    Path configDir;

    @CommandLine.Option(names = {"--yes"}, description = "Bỏ qua xác nhận")
    boolean skipConfirm;

    @Override
    public Integer call() throws Exception {
        Path baseDir = configDir != null ? configDir : ConfigLoader.jarDir();
        ConfigRegistry registry = ConfigRegistry.bootstrap(baseDir);
        AppConfig config = registry.current();
        config.validateSecretsForTrading();

        if (!skipConfirm) {
            System.out.println("CẢNH BÁO: sẽ đóng TẤT CẢ vị thế (mode=" + config.mode() + ").");
            System.out.println("Gõ 'YES' để xác nhận:");
            String line = new java.util.Scanner(System.in).nextLine();
            if (!"YES".equals(line.trim())) {
                System.out.println("Đã huỷ.");
                return 1;
            }
        }

        ExchangeFactory.Pair pair = ExchangeFactory.create(config, new RateLimiter());
        ExchangeClient client = pair.client();
        OrderGateway gateway = pair.gateway();
        FilterCache filterCache = new FilterCache(client, config.mode());
        filterCache.refresh();

        if (config.mode().isFutures()) {
            return closeFuturesPositions(client, gateway, filterCache);
        } else {
            return sellSpotBalances(client, gateway, filterCache);
        }
    }

    private int sellSpotBalances(ExchangeClient client, OrderGateway gateway, FilterCache filterCache)
            throws Exception {
        String accountJson = client.rawAccount();
        JsonNode root = new ObjectMapper().readTree(accountJson);
        int sold = 0, skipped = 0, failed = 0;
        for (JsonNode bal : root.get("balances")) {
            String asset = bal.get("asset").asText();
            if ("USDT".equals(asset)) continue;
            BigDecimal free = new BigDecimal(bal.get("free").asText());
            if (free.signum() <= 0) continue;

            String symbol = asset + "USDT";
            SymbolFilter filter = filterCache.get(symbol);
            if (filter == null) { skipped++; continue; }
            BigDecimal qty = FilterUtil.roundQtyDown(free, filter);
            if (qty == null) { skipped++; continue; }
            BigDecimal price = client.latestPrice(symbol);
            if (!FilterUtil.meetsMinNotional(qty, price, filter)) { skipped++; continue; }
            try {
                gateway.close(OrderGateway.CloseRequest.spotSell(
                        symbol, qty, "close-all-" + System.currentTimeMillis()));
                log.info("Sold {} {}", qty, asset);
                sold++;
            } catch (Exception e) {
                log.error("Sell {} lỗi: {}", symbol, e.getMessage());
                failed++;
            }
        }
        System.out.printf("close-all (spot) done: sold=%d, skipped=%d, failed=%d%n",
                sold, skipped, failed);
        return failed == 0 ? 0 : 2;
    }

    private int closeFuturesPositions(ExchangeClient client, OrderGateway gateway,
                                      FilterCache filterCache) throws Exception {
        String json = client.rawAccount();
        JsonNode root = new ObjectMapper().readTree(json);
        int closed = 0, skipped = 0, failed = 0;
        for (JsonNode p : root.path("positions")) {
            double amt = p.path("positionAmt").asDouble();
            if (amt == 0) continue;
            String symbol = p.get("symbol").asText();
            BigDecimal qty = new BigDecimal(p.get("positionAmt").asText()).abs();
            String side = amt > 0 ? "LONG" : "SHORT";
            SymbolFilter filter = filterCache.get(symbol);
            if (filter == null) { skipped++; continue; }
            BigDecimal qtyRounded = FilterUtil.roundQtyDown(qty, filter);
            if (qtyRounded == null) { skipped++; continue; }
            try {
                gateway.close(OrderGateway.CloseRequest.futuresClose(
                        symbol, qtyRounded, side,
                        "close-all-" + System.currentTimeMillis()));
                log.info("Closed futures {} {} {}", symbol, side, qtyRounded);
                closed++;
            } catch (Exception e) {
                log.error("Close futures {} lỗi: {}", symbol, e.getMessage());
                failed++;
            }
        }
        System.out.printf("close-all (futures) done: closed=%d, skipped=%d, failed=%d%n",
                closed, skipped, failed);
        return failed == 0 ? 0 : 2;
    }
}
