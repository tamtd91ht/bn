package vn.tamtd.bot.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.exchange.ExchangeClient;
import vn.tamtd.bot.indicator.TrendIndicators;
import vn.tamtd.bot.marketdata.BarSeriesCache;
import vn.tamtd.bot.notify.Notifier;
import vn.tamtd.bot.notify.NotifyEvent;
import vn.tamtd.bot.scanner.ScanResult;
import vn.tamtd.bot.scanner.UniverseScanner;
import vn.tamtd.bot.storage.BotState;
import vn.tamtd.bot.storage.Position;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrator per tick. Mode-agnostic (spot + futures).
 *
 * <p>Flow:
 * <ol>
 *   <li>Pause guard (kill-switch)</li>
 *   <li>Fetch prices</li>
 *   <li>Daily drawdown kill-switch</li>
 *   <li>LiquidationGuard (futures)</li>
 *   <li>PositionManager (TP/SL)</li>
 *   <li>Watchlist entry</li>
 *   <li>Scanner entry + rebalance</li>
 * </ol>
 */
public final class StrategyCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StrategyCoordinator.class);
    private static final Duration MISSED_ALERT_COOLDOWN = Duration.ofMinutes(60);

    private final ConfigRegistry configRegistry;
    private final ExchangeClient client;
    private final BarSeriesCache barSeriesCache;
    private final TrendIndicators trendIndicators;
    private final UniverseScanner scanner;
    private final PositionManager positionManager;
    private final EntryPlanner entryPlanner;
    private final RebalanceManager rebalanceManager;
    private final LiquidationGuard liquidationGuard;
    private final CapitalInitializer capitalInitializer;
    private final Notifier notifier;

    public StrategyCoordinator(ConfigRegistry configRegistry,
                               ExchangeClient client,
                               BarSeriesCache barSeriesCache,
                               TrendIndicators trendIndicators,
                               UniverseScanner scanner,
                               PositionManager positionManager,
                               EntryPlanner entryPlanner,
                               RebalanceManager rebalanceManager,
                               LiquidationGuard liquidationGuard,
                               CapitalInitializer capitalInitializer,
                               Notifier notifier) {
        this.configRegistry = configRegistry;
        this.client = client;
        this.barSeriesCache = barSeriesCache;
        this.trendIndicators = trendIndicators;
        this.scanner = scanner;
        this.positionManager = positionManager;
        this.entryPlanner = entryPlanner;
        this.rebalanceManager = rebalanceManager;
        this.liquidationGuard = liquidationGuard;
        this.capitalInitializer = capitalInitializer;
        this.notifier = notifier;
    }

    /** Slow tick: check positions + scan + entries. Đầy đủ như v1. */
    public List<Decision> tick(BotState state) {
        List<Decision> decisions = new ArrayList<>();
        if (isHaltedTick(state, "tick")) return decisions;
        AppConfig config = configRegistry.current();

        Map<String, BigDecimal> prices = fetchPrices(state, config, /*entriesNeeded*/ true);
        log.info("[STRATEGY] Fetched {} prices: {}", prices.size(), prices.keySet());

        List<Decision> killAndPos = evaluatePositions(state, prices, config);
        decisions.addAll(killAndPos);
        // Nếu vừa fire kill-switch, không plan entry nữa
        if (state.killedUntil != null && Instant.now().isBefore(state.killedUntil)) {
            return decisions;
        }

        decisions.addAll(planEntries(state, prices, config));
        return decisions;
    }

    /** Fast tick: chỉ check positions (TP/SL/kill-switch). KHÔNG scan, KHÔNG entry. */
    public List<Decision> tickPositions(BotState state) {
        List<Decision> decisions = new ArrayList<>();
        if (isHaltedTick(state, "fast-tick")) return decisions;
        if (state.positions.isEmpty()) return decisions;
        AppConfig config = configRegistry.current();

        Map<String, BigDecimal> prices = fetchPrices(state, config, /*entriesNeeded*/ false);
        log.info("[STRATEGY:FAST] Fetched {} prices: {}", prices.size(), prices.keySet());
        decisions.addAll(evaluatePositions(state, prices, config));
        return decisions;
    }

    private boolean isHaltedTick(BotState state, String label) {
        boolean killedActive = state.killedUntil != null && Instant.now().isBefore(state.killedUntil);
        if (state.paused || killedActive) {
            if (killedActive) {
                log.warn("[STRATEGY] Bot đang kill-switch cooldown (đến {}) - skip {}",
                        state.killedUntil, label);
            } else {
                log.warn("[STRATEGY] Bot đang pause thủ công - skip {}", label);
            }
            return true;
        }
        return false;
    }

    /** Kill-switch + per-position TP/SL/liquidation. Dùng chung cho tick chính lẫn fast-tick. */
    private List<Decision> evaluatePositions(BotState state,
                                             Map<String, BigDecimal> prices,
                                             AppConfig config) {
        List<Decision> decisions = new ArrayList<>();

        Optional<List<Decision>> killDecisions = checkKillSwitch(state, prices, config);
        if (killDecisions.isPresent()) {
            state.killedUntil = Instant.now().plusSeconds((long) config.risk().pauseHours() * 3600);
            decisions.addAll(killDecisions.get());
            return decisions;
        }

        String tfPrimary = config.timeframes().primary();
        int posEval = 0;
        for (Position p : new ArrayList<>(state.positions.values())) {
            BigDecimal price = prices.get(p.symbol);
            if (price == null) continue;
            posEval++;

            Optional<Decision> liqDec = liquidationGuard.evaluate(p, price);
            if (liqDec.isPresent()) {
                decisions.add(liqDec.get());
                continue;
            }

            try {
                BarSeries s = barSeriesCache.get(p.symbol, tfPrimary);
                double pnl = positionManager.computePnlPct(p, price);
                log.info("[STRATEGY] Position {} entry={} cur={} pnl={}% qty={} tp={} sl={} lev={} src={}",
                        p.symbol, p.entryPrice, price, String.format("%+.2f", pnl),
                        p.qty, String.format("%.2f", p.currentTpPct),
                        String.format("%.2f", p.currentSlPct),
                        p.leverage, p.source);
                Optional<Decision> opt = positionManager.evaluate(p, s, price);
                opt.ifPresent(decisions::add);
            } catch (Exception e) {
                log.warn("[STRATEGY] Evaluate {} lỗi: {}", p.symbol, e.getMessage());
            }
        }
        log.info("[STRATEGY] Đánh giá {} position", posEval);
        return decisions;
    }

    /** Watchlist + scanner entry plan + missed alerts + rebalance. Chỉ dùng ở tick chính. */
    private List<Decision> planEntries(BotState state,
                                       Map<String, BigDecimal> prices,
                                       AppConfig config) {
        List<Decision> decisions = new ArrayList<>();
        List<Decision.EntryBuy> wlEntries = entryPlanner.planWatchlistEntries(state);
        decisions.addAll(wlEntries);

        if (config.scanner().enabled()) {
            List<ScanResult> scanResults = scanner.scan();
            List<Decision.EntryBuy> scannerEntries = entryPlanner.planScannerEntries(state, scanResults);
            decisions.addAll(scannerEntries);

            alertMissedOpportunities(state, scanResults, scannerEntries, config);

            boolean hasPendingOpportunity = scanResults.stream()
                    .anyMatch(r -> r.signal() != ScanResult.Signal.NONE)
                    && scannerEntries.isEmpty()
                    && !state.positions.isEmpty();
            if (hasPendingOpportunity) {
                rebalanceManager.findCoinToFreeReserve(state, prices).ifPresent(decisions::add);
            }
        }
        return decisions;
    }

    private void alertMissedOpportunities(BotState state,
                                          List<ScanResult> scanResults,
                                          List<Decision.EntryBuy> scannerEntries,
                                          AppConfig config) {
        Set<String> entered = new HashSet<>();
        for (Decision.EntryBuy b : scannerEntries) entered.add(b.symbol());

        Instant now = Instant.now();
        List<ScanResult> missed = new ArrayList<>();
        for (ScanResult r : scanResults) {
            if (r.signal() == ScanResult.Signal.NONE) continue;
            if (entered.contains(r.symbol())) continue;
            if (state.positions.containsKey(r.symbol())) continue;
            Instant cdUntil = state.cooldowns.get(r.symbol());
            if (cdUntil != null && now.isBefore(cdUntil)) continue;
            missed.add(r);
        }
        if (missed.isEmpty()) return;

        double minSize = config.capital().minTradeSizeUsdt();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "Ví không đủ vào lệnh (reserveFund=%.2f USDT, minTradeSize=%.2f USDT) nhưng có %d coin có signal mạnh:",
                state.reserveFund, minSize, missed.size()));
        List<ScanResult> toAlert = new ArrayList<>();
        for (ScanResult r : missed) {
            Instant alertUntil = state.alertCooldowns.get(r.symbol());
            boolean onAlertCd = alertUntil != null && now.isBefore(alertUntil);
            sb.append(String.format("%n  • %s signal=%s score=%.2f qv24h=%,.0f%s",
                    r.symbol(), r.signal(), r.score(), r.quoteVolume24h(),
                    onAlertCd ? " (đã alert)" : ""));
            if (!onAlertCd) toAlert.add(r);
        }
        log.warn("[MISS] {}", sb);
        if (toAlert.isEmpty()) return;
        notifier.send(NotifyEvent.OPPORTUNITY_MISSED, sb.toString());
        Instant next = now.plus(MISSED_ALERT_COOLDOWN);
        for (ScanResult r : toAlert) state.alertCooldowns.put(r.symbol(), next);
    }

    private Optional<List<Decision>> checkKillSwitch(BotState state,
                                                     Map<String, BigDecimal> prices,
                                                     AppConfig config) {
        if (state.v0 <= 0) return Optional.empty();
        if (state.positions.isEmpty()) return Optional.empty();

        // Tính unrealized P&L dựa trên chính các position bot đang mở, so với v0.
        // Không fetch total equity từ Binance vì user có thể nạp/rút USDT thủ công
        // → lỗi v1 cũ: kill-switch fire khi equity giảm vì rút tiền, trong khi các vị thế đang lãi.
        double unrealizedPnl = 0.0;
        double bookNotional = 0.0;
        int priced = 0;
        for (Position p : state.positions.values()) {
            BigDecimal price = prices.get(p.symbol);
            if (price == null || price.signum() == 0 || p.qty == null) continue;
            priced++;
            BigDecimal diff = p.isLong()
                    ? price.subtract(p.entryPrice)
                    : p.entryPrice.subtract(price);
            unrealizedPnl += diff.multiply(p.qty).doubleValue();
            bookNotional += p.entryPrice.multiply(p.qty).doubleValue();
        }
        if (priced == 0) {
            log.warn("[KILL-SWITCH] Không có price cho position nào - skip kill-switch tick này");
            return Optional.empty();
        }
        // Unrealized lỗ > ngưỡng drawdown (% v0) → tăng counter; nếu không lỗ thì reset.
        double drawdown = -unrealizedPnl / state.v0;
        double threshold = config.risk().dailyDrawdownPct();
        int hysteresis = Math.max(1, config.risk().killSwitchHysteresisTicksV());

        if (drawdown < threshold) {
            if (state.killSwitchTriggerCount > 0) {
                log.info("[KILL-SWITCH] Recovery: drawdown {} < threshold {} → reset counter từ {} về 0",
                        String.format("%.2f%%", drawdown * 100),
                        String.format("%.2f%%", threshold * 100),
                        state.killSwitchTriggerCount);
                state.killSwitchTriggerCount = 0;
            }
            return Optional.empty();
        }

        state.killSwitchTriggerCount++;
        if (state.killSwitchTriggerCount < hysteresis) {
            log.warn("[KILL-SWITCH] Drawdown {}% ≥ {}% nhưng mới {} / {} tick liên tiếp - chờ thêm",
                    String.format("%.2f", drawdown * 100),
                    String.format("%.2f", threshold * 100),
                    state.killSwitchTriggerCount, hysteresis);
            return Optional.empty();
        }

        log.error("=== KILL-SWITCH (sau {} tick liên tiếp drawdown ≥ ngưỡng) ===", hysteresis);
        log.error("unrealizedPnl={} USDT bookNotional={} v0={} drawdown={}% > ngưỡng {}% → BÁN HẾT, pause {}h",
                String.format("%.4f", unrealizedPnl),
                String.format("%.4f", bookNotional),
                String.format("%.2f", state.v0),
                String.format("%.2f", drawdown * 100),
                String.format("%.2f", threshold * 100),
                config.risk().pauseHours());
        state.killSwitchTriggerCount = 0;
        List<Decision> all = new ArrayList<>();
        for (Position p : state.positions.values()) {
            all.add(new Decision.KillSwitchSellAll(p.symbol, p.qty,
                    "Kill-switch: unrealized drawdown " + String.format("%.2f%%", drawdown * 100)));
        }
        return Optional.of(all);
    }

    private Map<String, BigDecimal> fetchPrices(BotState state, AppConfig config, boolean entriesNeeded) {
        Map<String, BigDecimal> map = new HashMap<>();
        Set<String> wanted = new HashSet<>();
        wanted.addAll(state.positions.keySet());
        if (entriesNeeded) wanted.addAll(config.watchlist().symbols());
        for (String symbol : wanted) {
            try { map.put(symbol, client.latestPrice(symbol)); }
            catch (Exception e) { log.warn("Fetch price {} lỗi: {}", symbol, e.getMessage()); }
        }
        return map;
    }
}
