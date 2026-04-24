package vn.tamtd.bot.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import vn.tamtd.bot.app.control.TelegramController;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigFileWatcher;
import vn.tamtd.bot.config.ConfigLoader;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.exchange.ExchangeClient;
import vn.tamtd.bot.exchange.ExchangeFactory;
import vn.tamtd.bot.exchange.FilterCache;
import vn.tamtd.bot.exchange.OrderGateway;
import vn.tamtd.bot.exchange.RateLimiter;
import vn.tamtd.bot.exchange.TimeSync;
import vn.tamtd.bot.executor.OrderExecutor;
import vn.tamtd.bot.indicator.SignalDetector;
import vn.tamtd.bot.indicator.TrendIndicators;
import vn.tamtd.bot.marketdata.BarSeriesCache;
import vn.tamtd.bot.marketdata.KlineFetcher;
import vn.tamtd.bot.notify.NoOpNotifier;
import vn.tamtd.bot.notify.Notifier;
import vn.tamtd.bot.notify.NotifyEvent;
import vn.tamtd.bot.notify.TelegramNotifier;
import vn.tamtd.bot.scanner.UniverseScanner;
import vn.tamtd.bot.storage.BotState;
import vn.tamtd.bot.storage.CleanupJob;
import vn.tamtd.bot.storage.JsonlWriter;
import vn.tamtd.bot.storage.Position;
import vn.tamtd.bot.storage.StateStore;
import vn.tamtd.bot.strategy.CapitalInitializer;
import vn.tamtd.bot.strategy.EntryPlanner;
import vn.tamtd.bot.strategy.FundingRateGuard;
import vn.tamtd.bot.strategy.LiquidationGuard;
import vn.tamtd.bot.strategy.PositionManager;
import vn.tamtd.bot.strategy.RebalanceManager;
import vn.tamtd.bot.strategy.StrategyCoordinator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live trading entry. Wire tất cả dependency qua {@link ConfigRegistry}
 * + {@link ExchangeFactory} để chọn đúng client (spot/futures) theo config.
 */
@CommandLine.Command(
        name = "run",
        description = "Chạy bot live trading (đặt lệnh thực)"
)
public final class RunCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(RunCommand.class);

    @CommandLine.Option(names = {"-c", "--config-dir"},
            description = "Thư mục chứa app.yml và symbols.yml (mặc định: thư mục jar)")
    Path configDir;

    @Override
    public Integer call() throws Exception {
        log.info("=== Binance Auto-Trading Bot - starting ===");
        Path baseDir = configDir != null ? configDir : ConfigLoader.jarDir();
        ConfigRegistry registry = ConfigRegistry.bootstrap(baseDir);
        AppConfig config = registry.current();
        config.validateSecretsForTrading();
        log.info("Mode: {}, leverage={}, testnet: {}, watchlist: {}",
                config.exchange().mode(), config.exchange().leverage(),
                config.exchange().useTestnet(), config.watchlist().symbols());
        if (!config.exchange().useTestnet()) {
            log.warn("!!! LIVE trading mode - bot sẽ đặt lệnh bằng vốn thực !!!");
        }

        Notifier notifier = config.telegram().enabled()
                ? new TelegramNotifier(registry)
                : new NoOpNotifier();

        RateLimiter rateLimiter = new RateLimiter();
        ExchangeFactory.Pair pair = ExchangeFactory.create(config, rateLimiter);
        ExchangeClient exchangeClient = pair.client();
        OrderGateway gateway = pair.gateway();

        TimeSync timeSync = new TimeSync(exchangeClient);
        timeSync.start();

        FilterCache filterCache = new FilterCache(exchangeClient, config.mode());
        filterCache.refresh();

        Path dataDir = Paths.get(config.storage().dataDir());
        StateStore stateStore = new StateStore(dataDir);
        BotState state = stateStore.load();
        log.info("State loaded: v0={}, reserveFund={}, positions={}, killedUntil={}, paused={}",
                state.v0, state.reserveFund, state.positions.keySet(), state.killedUntil, state.paused);

        JsonlWriter jsonlWriter = new JsonlWriter(dataDir);
        CleanupJob cleanupJob = new CleanupJob(
                dataDir,
                config.storage().keepDays(),
                config.scheduling().cleanupHourVN()
        );
        cleanupJob.start();

        KlineFetcher klineFetcher = new KlineFetcher(exchangeClient);
        BarSeriesCache barSeriesCache = new BarSeriesCache(klineFetcher);
        TrendIndicators trendIndicators = new TrendIndicators(registry);
        SignalDetector signalDetector = new SignalDetector(registry);
        UniverseScanner scanner = new UniverseScanner(registry, exchangeClient, barSeriesCache, signalDetector);

        FundingRateGuard fundingGuard = new FundingRateGuard(registry, exchangeClient, notifier);
        LiquidationGuard liquidationGuard = new LiquidationGuard(registry, notifier);

        PositionManager positionManager = new PositionManager(registry, trendIndicators, filterCache);
        EntryPlanner entryPlanner = new EntryPlanner(registry, trendIndicators, barSeriesCache, fundingGuard);
        RebalanceManager rebalanceManager = new RebalanceManager(registry, trendIndicators, barSeriesCache);
        CapitalInitializer capitalInitializer = new CapitalInitializer(registry, exchangeClient);
        StrategyCoordinator coordinator = new StrategyCoordinator(
                registry, exchangeClient, barSeriesCache, trendIndicators, scanner,
                positionManager, entryPlanner, rebalanceManager, liquidationGuard,
                capitalInitializer, notifier);
        OrderExecutor orderExecutor = new OrderExecutor(
                registry, exchangeClient, gateway, filterCache, jsonlWriter, stateStore, notifier);

        TickLoop tickLoop = new TickLoop(
                registry, capitalInitializer, coordinator, orderExecutor,
                jsonlWriter, stateStore, state);

        // Hot-reload
        ConfigFileWatcher fileWatcher = new ConfigFileWatcher(registry);
        fileWatcher.start();
        registry.addListener(cfg -> notifier.send(NotifyEvent.CONFIG_RELOADED,
                String.format("Config reload: mode=%s lev=%s tp=%.1f%% sl=%.1f%% watchlist=%s",
                        cfg.exchange().mode(), cfg.exchange().leverage(),
                        cfg.exit().takeProfitPctV(), cfg.exit().stopLossPctV(),
                        cfg.watchlist().symbols())));

        // Telegram control
        TelegramController tgController = new TelegramController(
                registry, state, stateStore, exchangeClient, orderExecutor);
        tgController.start();

        // Scheduler tick
        ScheduledExecutorService tickScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tick-loop");
            t.setDaemon(false);
            return t;
        });
        long tickSec = TimeUnit.MINUTES.toSeconds(config.scheduling().tickMinutes());
        tickScheduler.scheduleAtFixedRate(tickLoop::safeTick, 5, tickSec, TimeUnit.SECONDS);
        log.info("TickLoop scheduled: mỗi {} phút", config.scheduling().tickMinutes());

        String hostname = System.getenv().getOrDefault("HOSTNAME",
                System.getenv().getOrDefault("COMPUTERNAME", "unknown-host"));
        String startedAt = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
        String startMsg = buildStartupMessage(hostname, startedAt, config, state,
                capitalInitializer, exchangeClient);
        log.info("=== APP_START ===");
        for (String line : startMsg.split("\n")) log.info("  {}", line);
        notifier.send(NotifyEvent.APP_START, startMsg);

        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down...");
            try {
                String stoppedAt = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
                String stopMsg = String.format(
                        "Bot STOPPED on %s at %s | v0=%.2f reserveFund=%.2f positions=%s",
                        hostname, stoppedAt, state.v0, state.reserveFund, state.positions.keySet());
                log.info("=== APP_STOP === {}", stopMsg);
                notifier.send(NotifyEvent.APP_STOP, stopMsg);
            } catch (Exception e) {
                log.warn("APP_STOP notify lỗi: {}", e.getMessage());
            }
            tgController.stop();
            fileWatcher.stop();
            tickScheduler.shutdownNow();
            timeSync.stop();
            cleanupJob.stop();
            stateStore.save(state);
            shutdown.countDown();
        }, "shutdown-hook"));

        shutdown.await();
        log.info("Bot stopped.");
        return 0;
    }

    private static String buildStartupMessage(String hostname, String startedAt,
                                              AppConfig config,
                                              BotState state,
                                              CapitalInitializer capitalInitializer,
                                              ExchangeClient client) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Bot STARTED on %s at %s%n", hostname, startedAt));
        sb.append(String.format("Mode=%s lev=%s testnet=%s scanner=%s tick=%dm%n",
                config.exchange().mode(), config.exchange().leverage(),
                config.exchange().useTestnet(),
                config.scanner().enabled(), config.scheduling().tickMinutes()));
        sb.append(String.format("State: v0=%.2f reserveFund=%.2f tracked=%s paused=%s%n",
                state.v0, state.reserveFund, state.positions.keySet(), state.paused));

        try {
            CapitalInitializer.AccountSnapshot snap = capitalInitializer.fetchAccountSnapshot();
            sb.append(String.format("─── Ví hiện tại: equity=%.4f USDT ───%n", snap.totalEquityUsdt()));
            if (snap.holdings().isEmpty()) {
                sb.append("  (ví rỗng)\n");
            } else {
                var sorted = new java.util.ArrayList<>(snap.holdings());
                sorted.sort(java.util.Comparator.comparingDouble(
                        CapitalInitializer.AssetHolding::usdtValue).reversed());
                for (CapitalInitializer.AssetHolding h : sorted) {
                    sb.append(String.format("  • %s: amount=%.8f ≈ %.4f USDT [%s]%n",
                            h.asset(), h.amount(), h.usdtValue(), h.source()));
                }
            }
        } catch (Exception e) {
            sb.append(String.format("  (lấy snapshot ví lỗi: %s)%n", e.getMessage()));
        }

        sb.append(String.format("─── Watchlist (%d) ───%n", config.watchlist().symbols().size()));
        for (String symbol : config.watchlist().symbols()) {
            try {
                BigDecimal price = client.latestPrice(symbol);
                Position p = state.positions.get(symbol);
                if (p != null) {
                    BigDecimal diff = p.isLong()
                            ? price.subtract(p.entryPrice)
                            : p.entryPrice.subtract(price);
                    int lev = p.leverage == null ? 1 : p.leverage;
                    double pnlPct = diff.divide(p.entryPrice, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100L * lev)).doubleValue();
                    sb.append(String.format("  • %s = %s [HOLD qty=%s entry=%s pnl=%+.2f%%]%n",
                            symbol, price.toPlainString(),
                            p.qty.toPlainString(), p.entryPrice.toPlainString(), pnlPct));
                } else {
                    sb.append(String.format("  • %s = %s%n", symbol, price.toPlainString()));
                }
            } catch (Exception e) {
                sb.append(String.format("  • %s = (lỗi %s)%n", symbol, e.getMessage()));
            }
        }
        return sb.toString().stripTrailing();
    }
}
