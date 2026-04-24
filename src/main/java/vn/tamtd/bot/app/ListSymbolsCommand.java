package vn.tamtd.bot.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * In top-N USDT pair theo volume 24h. Hoạt động cho cả spot và futures.
 */
@CommandLine.Command(
        name = "list-symbols",
        description = "Top-N USDT pair theo volume 24h"
)
public final class ListSymbolsCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-c", "--config-dir"},
            description = "Thư mục config")
    Path configDir;

    @CommandLine.Option(names = {"-n", "--top"}, defaultValue = "30")
    int top;

    @Override
    public Integer call() throws Exception {
        Path baseDir = configDir != null ? configDir : ConfigLoader.jarDir();
        ConfigRegistry registry = ConfigRegistry.bootstrap(baseDir);
        AppConfig config = registry.current();
        ExchangeFactory.Pair pair = ExchangeFactory.create(config, new RateLimiter());
        ExchangeClient client = pair.client();

        String json = client.rawAll24hTicker();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode arr = mapper.readTree(json);

        Set<String> blacklist = Set.copyOf(config.scanner().blacklist());
        Set<String> watchlist = Set.copyOf(config.watchlist().symbols());
        double minVol = config.scanner().minQuoteVolume24hUsdt();

        record Row(String symbol, BigDecimal price, BigDecimal changePct, BigDecimal quoteVolume) {}
        List<Row> rows = new ArrayList<>();
        for (JsonNode t : arr) {
            String symbol = t.get("symbol").asText();
            if (!symbol.endsWith("USDT")) continue;
            if (blacklist.contains(symbol)) continue;
            BigDecimal qv = new BigDecimal(t.get("quoteVolume").asText());
            if (qv.doubleValue() < minVol) continue;
            BigDecimal price = new BigDecimal(t.get("lastPrice").asText());
            BigDecimal change = new BigDecimal(t.get("priceChangePercent").asText());
            rows.add(new Row(symbol, price, change, qv));
        }
        rows.sort(Comparator.comparing(Row::quoteVolume).reversed());

        System.out.printf("%-12s %-15s %-10s %-20s %-10s%n",
                "SYMBOL", "PRICE", "24H%", "QUOTE_VOLUME(USDT)", "WATCHLIST");
        System.out.println("-".repeat(72));
        rows.stream().limit(top).forEach(r ->
                System.out.printf("%-12s %-15s %-10s %-20s %-10s%n",
                        r.symbol(), r.price().toPlainString(),
                        r.changePct().toPlainString() + "%",
                        String.format("%,.0f", r.quoteVolume()),
                        watchlist.contains(r.symbol()) ? "[W]" : ""));
        System.out.println();
        System.out.printf("Mode=%s total %d symbol thoả minQuoteVolume %.0f%n",
                config.mode(), rows.size(), minVol);
        return 0;
    }
}
