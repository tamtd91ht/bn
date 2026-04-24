package vn.tamtd.bot.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import picocli.CommandLine;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigLoader;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.exchange.ExchangeClient;
import vn.tamtd.bot.exchange.ExchangeFactory;
import vn.tamtd.bot.exchange.RateLimiter;
import vn.tamtd.bot.indicator.SignalDetector;
import vn.tamtd.bot.indicator.TrendIndicators;
import vn.tamtd.bot.marketdata.BarSeriesCache;
import vn.tamtd.bot.marketdata.KlineFetcher;
import vn.tamtd.bot.scanner.ScanResult;
import vn.tamtd.bot.scanner.UniverseScanner;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 1 vòng scan + phân tích watchlist, in kết quả rồi exit. Không đặt lệnh.
 */
@CommandLine.Command(
        name = "scan-once",
        description = "1 vòng scan + phân tích watchlist"
)
public final class ScanOnceCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(ScanOnceCommand.class);

    @CommandLine.Option(names = {"-c", "--config-dir"}, description = "Thư mục config")
    Path configDir;

    @Override
    public Integer call() throws Exception {
        Path baseDir = configDir != null ? configDir : ConfigLoader.jarDir();
        ConfigRegistry registry = ConfigRegistry.bootstrap(baseDir);
        AppConfig config = registry.current();

        ExchangeFactory.Pair pair = ExchangeFactory.create(config, new RateLimiter());
        ExchangeClient client = pair.client();
        KlineFetcher fetcher = new KlineFetcher(client);
        BarSeriesCache cache = new BarSeriesCache(fetcher);
        TrendIndicators trendIndicators = new TrendIndicators(registry);
        SignalDetector signalDetector = new SignalDetector(registry);
        UniverseScanner scanner = new UniverseScanner(registry, client, cache, signalDetector);

        String tfPrimary = config.timeframes().primary();
        System.out.println("=== WATCHLIST (" + tfPrimary + ", mode=" + config.mode() + ") ===");
        System.out.printf("%-12s %-12s %-10s %-8s%n", "SYMBOL", "PRICE", "TREND", "RSI");
        for (String symbol : config.watchlist().symbols()) {
            try {
                BarSeries s = cache.get(symbol, tfPrimary);
                TrendIndicators.Trend trend = trendIndicators.classify(s);
                double rsi = trendIndicators.rsi(s);
                double price = trendIndicators.lastClose(s);
                System.out.printf("%-12s %-12s %-10s %-8s%n",
                        symbol, String.format("%.8f", price), trend,
                        String.format("%.1f", rsi));
            } catch (Exception e) {
                System.out.printf("%-12s ERROR: %s%n", symbol, e.getMessage());
            }
        }

        if (!config.scanner().enabled()) {
            System.out.println("\n(Scanner disabled)");
            return 0;
        }

        System.out.println("\n=== SCANNER ===");
        long start = System.currentTimeMillis();
        List<ScanResult> results = scanner.scan();
        long elapsed = System.currentTimeMillis() - start;

        int sig = 0;
        System.out.printf("%-12s %-18s %-8s %-18s %-8s%n",
                "SYMBOL", "QUOTE_VOLUME", "24H%", "SIGNAL", "SCORE");
        for (ScanResult r : results) {
            if (r.signal() != ScanResult.Signal.NONE) sig++;
            System.out.printf("%-12s %-18s %-8s %-18s %-8s%n",
                    r.symbol(), String.format("%,.0f", r.quoteVolume24h()),
                    String.format("%.2f%%", r.pctChange24h()),
                    r.signal(), String.format("%.2f", r.score()));
        }
        System.out.printf("%n%d/%d signal (%dms)%n", sig, results.size(), elapsed);
        return 0;
    }
}
