package vn.tamtd.bot.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.config.ExchangeMode;
import vn.tamtd.bot.exchange.BinanceFuturesClient;
import vn.tamtd.bot.exchange.ExchangeClient;
import vn.tamtd.bot.exchange.FilterCache;
import vn.tamtd.bot.exchange.FilterUtil;
import vn.tamtd.bot.exchange.OrderGateway;
import vn.tamtd.bot.exchange.SymbolFilter;
import vn.tamtd.bot.notify.NotifyEvent;
import vn.tamtd.bot.notify.Notifier;
import vn.tamtd.bot.storage.BotState;
import vn.tamtd.bot.storage.JsonlWriter;
import vn.tamtd.bot.storage.Position;
import vn.tamtd.bot.storage.StateStore;
import vn.tamtd.bot.strategy.AccountCache;
import vn.tamtd.bot.strategy.Decision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thực thi {@link Decision} thành lệnh Binance qua {@link OrderGateway}.
 * Hoạt động cho cả Spot (quote-based buy) và Futures (qty-based + leverage + reduceOnly).
 *
 * <p>Mỗi entry/exit: parse response → update {@link BotState} → ghi JSONL events.
 */
public final class OrderExecutor {

    private static final Logger log = LoggerFactory.getLogger(OrderExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConfigRegistry configRegistry;
    private final ExchangeClient exchangeClient;
    private final OrderGateway gateway;
    private final FilterCache filterCache;
    private final JsonlWriter jsonlWriter;
    private final StateStore stateStore;
    private final Notifier notifier;
    private final AccountCache accountCache;

    public OrderExecutor(ConfigRegistry configRegistry,
                         ExchangeClient exchangeClient,
                         OrderGateway gateway,
                         FilterCache filterCache,
                         JsonlWriter jsonlWriter,
                         StateStore stateStore,
                         Notifier notifier,
                         AccountCache accountCache) {
        this.configRegistry = configRegistry;
        this.exchangeClient = exchangeClient;
        this.gateway = gateway;
        this.filterCache = filterCache;
        this.jsonlWriter = jsonlWriter;
        this.stateStore = stateStore;
        this.notifier = notifier;
        this.accountCache = accountCache;
    }

    public void execute(Decision decision, BotState state, long tickTs) {
        AppConfig config = configRegistry.current();
        log.info("[EXEC] → {} symbol={} reason=\"{}\"",
                decision.getClass().getSimpleName(), decision.symbol(), decision.reason());
        try {
            if (decision instanceof Decision.EntryBuy d) {
                executeEntry(d, state, tickTs, config);
            } else if (decision instanceof Decision.TakeProfitPartial d) {
                executeClose(d.symbol(), d.qtyToSell(), "TP_PARTIAL",
                        d.reason(), state, tickTs, false);
            } else if (decision instanceof Decision.TakeProfitFull d) {
                executeClose(d.symbol(), d.qtyToSell(), "TP_FULL",
                        d.reason(), state, tickTs, true);
            } else if (decision instanceof Decision.StopLoss d) {
                executeClose(d.symbol(), d.qtyToSell(), "STOP_LOSS",
                        d.reason(), state, tickTs, true);
            } else if (decision instanceof Decision.RebalanceSell d) {
                executeClose(d.symbol(), d.qtyToSell(), "REBALANCE",
                        d.reason(), state, tickTs, true);
            } else if (decision instanceof Decision.KillSwitchSellAll d) {
                executeClose(d.symbol(), d.qtyToSell(), "KILL_SWITCH",
                        d.reason(), state, tickTs, true);
            }
        } catch (Exception e) {
            log.error("[EXEC:FAIL] Execute decision {} lỗi: {}", decision, e.getMessage(), e);
            notifier.send(NotifyEvent.ERROR, String.format(
                    "Execute %s failed: %s", decision.symbol(), e.getMessage()));
        }
    }

    // ===== ENTRY =====

    private void executeEntry(Decision.EntryBuy d, BotState state, long tickTs, AppConfig config) {
        SymbolFilter filter = filterCache.get(d.symbol());
        if (filter == null) {
            log.warn("[BUY:REJECT] {} - không có filter, bỏ qua entry", d.symbol());
            return;
        }
        BigDecimal quote = d.quoteAmount().setScale(filter.quoteAssetPrecision(),
                RoundingMode.DOWN);

        // Cap theo FREE USDT thật trên sàn (không tin state.reserveFund cache) -
        // user có thể vừa rút USDT, hoặc lệnh trước đã ăn fee.
        // Nếu quote > freeUsdt thì cắt xuống freeUsdt × 0.999 (buffer precision).
        BigDecimal freeUsdt = accountCache.freeUsdt();
        if (freeUsdt.signum() > 0 && quote.compareTo(freeUsdt) > 0) {
            BigDecimal capped = freeUsdt.multiply(BigDecimal.valueOf(0.999))
                    .setScale(filter.quoteAssetPrecision(), RoundingMode.DOWN);
            log.warn("[BUY:CAP] {} quote {} > freeUsdt {} → cap xuống {}",
                    d.symbol(), quote.toPlainString(), freeUsdt.toPlainString(),
                    capped.toPlainString());
            quote = capped;
        }
        if (quote.compareTo(filter.minNotional()) < 0) {
            log.warn("[BUY:REJECT] {} quote={} < MIN_NOTIONAL={} (freeUsdt={}) - bỏ qua",
                    d.symbol(), quote, filter.minNotional(), freeUsdt.toPlainString());
            return;
        }

        ExchangeMode mode = config.mode();
        String clientOrderId = clientOrderId(mode.isFutures() ? "F-E" : "E", d.symbol(), tickTs);
        int leverage = config.leverageFor(d.symbol());
        log.info("[BUY:SUBMIT] {} mode={} lev={} quote={} USDT source={} cid={}",
                d.symbol(), mode, leverage, quote.toPlainString(), d.source(), clientOrderId);

        long t0 = System.currentTimeMillis();
        OrderGateway.OpenRequest req;
        if (mode.isFutures()) {
            BigDecimal markPrice = exchangeClient.latestPrice(d.symbol());
            BigDecimal notional = quote.multiply(BigDecimal.valueOf(leverage));
            BigDecimal rawQty = notional.divide(markPrice, 12, RoundingMode.DOWN);
            BigDecimal qty = FilterUtil.roundQtyDown(rawQty, filter);
            if (qty == null) {
                log.warn("[BUY:REJECT] {} qty làm tròn < minQty filter", d.symbol());
                return;
            }
            req = OrderGateway.OpenRequest.byQty(d.symbol(), qty, clientOrderId);
        } else {
            req = OrderGateway.OpenRequest.byQuote(d.symbol(), quote, clientOrderId);
        }

        String resp;
        try {
            resp = gateway.openLong(req);
        } catch (Exception e) {
            log.error("[BUY:FAIL] {} API lỗi: {}", d.symbol(), e.getMessage());
            throw e;
        }
        long latency = System.currentTimeMillis() - t0;
        OrderFillResult fill = parseOrder(resp, d.symbol(), "BUY", mode);
        log.info("[BUY:RESP] {} status={} orderId={} executedQty={} cumQuote={} avgPrice={} latency={}ms",
                d.symbol(), fill.status(), fill.orderId(),
                fill.executedQty().toPlainString(),
                fill.cummulativeQuoteQty().toPlainString(),
                fill.avgPrice().toPlainString(), latency);
        logOrderEvent(fill, "BUY", d.source(), d.reason());

        if (!fill.isFilled() || fill.executedQty().signum() == 0) {
            log.warn("[BUY:NOT_FILLED] {} status={}", d.symbol(), fill.status());
            return;
        }
        // Free USDT/base asset đã thay đổi → lần read kế tiếp phải fetch lại
        accountCache.invalidate();

        // === Update state ===
        double reserveBefore = state.reserveFund;
        boolean isScannerSource = d.source() != null && d.source().startsWith("SCANNER");
        if (isScannerSource) {
            state.reserveFund -= fill.cummulativeQuoteQty().doubleValue();
            if (state.reserveFund < 0) state.reserveFund = 0;
        }
        AppConfig.Exit exitCfg = config.exitFor(d.symbol());
        Position existing = state.positions.get(d.symbol());
        boolean isTopUp = existing != null && d.source() != null && d.source().endsWith("_TOPUP");
        Position p;
        if (isTopUp) {
            // Top-up: weighted-avg entry + cộng qty vào position hiện tại, KHÔNG ghi đè.
            // Giữ currentTpPct / currentSlPct hiện tại (ngưỡng trailing đã tiến lên sau partial).
            existing.mergeTopUp(fill.executedQty(), fill.avgPrice());
            p = existing;
            log.info("[BUY:TOPUP] {} merged qty+={} @ {} → total qty={} newAvgEntry={} topUpCount={}",
                    d.symbol(),
                    fill.executedQty().toPlainString(),
                    fill.avgPrice().toPlainString(),
                    p.qty.toPlainString(),
                    p.entryPrice.toPlainString(),
                    p.topUpCount);
        } else {
            if (mode.isFutures()) {
                BigDecimal liqPrice = fetchLiquidationPrice(d.symbol());
                p = Position.futuresEntry(d.symbol(), "LONG",
                        fill.executedQty(), fill.avgPrice(),
                        leverage, liqPrice, d.source(),
                        exitCfg.takeProfitPctV(), -exitCfg.stopLossPctV());
            } else {
                p = Position.spotEntry(d.symbol(),
                        fill.executedQty(), fill.avgPrice(), d.source(),
                        exitCfg.takeProfitPctV(), -exitCfg.stopLossPctV());
            }
            state.positions.put(d.symbol(), p);
        }
        stateStore.save(state);

        log.info("[BUY:FILLED] {} qty={} @ avg={} notional={} margin={} tp={}% sl={}% lev={} liq={} reserve {}→{}{}",
                d.symbol(),
                fill.executedQty().toPlainString(),
                fill.avgPrice().toPlainString(),
                p.notionalUsdt == null ? "n/a" : p.notionalUsdt.toPlainString(),
                p.marginUsdt == null ? "n/a" : p.marginUsdt.toPlainString(),
                String.format("%.2f", p.currentTpPct),
                String.format("%.2f", p.currentSlPct),
                p.leverage, p.liquidationPrice,
                String.format("%.4f", reserveBefore),
                String.format("%.4f", state.reserveFund),
                isTopUp ? " [TOP-UP]" : "");

        notifier.send(NotifyEvent.ENTRY, String.format(
                "%s %s %s @ %s (qty=%s) lev=%sx source=%s",
                isTopUp ? "TOP-UP" : "ENTRY",
                d.symbol(),
                fill.cummulativeQuoteQty().toPlainString() + " USDT notional",
                fill.avgPrice().toPlainString(),
                fill.executedQty().toPlainString(),
                p.leverage == null ? "1" : p.leverage.toString(),
                d.source()));
    }

    // ===== CLOSE =====

    private void executeClose(String symbol, BigDecimal qtyRequested, String type,
                              String reason, BotState state, long tickTs, boolean closesPosition) {
        AppConfig config = configRegistry.current();
        ExchangeMode mode = config.mode();
        SymbolFilter filter = filterCache.get(symbol);
        if (filter == null) {
            log.warn("[SELL:REJECT] {} - không có filter, bỏ qua", symbol);
            return;
        }
        Position position = state.positions.get(symbol);
        if (position == null) {
            log.warn("[SELL:REJECT] Không có position {} - bỏ qua", symbol);
            return;
        }

        // Cap qty theo free balance thật của base asset (SPOT only) - user có thể vừa mua/bán
        // thủ công trên app Binance, làm state.positions.qty lệch với số coin thực có trong ví.
        // Nếu bán quá free → Binance trả -2010. Futures dùng reduceOnly tách biệt, không cap ở đây.
        BigDecimal targetQty = qtyRequested.min(position.qty);
        if (!mode.isFutures()) {
            BigDecimal freeBase = accountCache.freeAsset(filter.baseAsset());
            if (freeBase.signum() > 0 && freeBase.compareTo(targetQty) < 0) {
                log.warn("[SELL:CAP] {} requested={} > freeBase {} {} → cap xuống free",
                        symbol, targetQty.toPlainString(),
                        freeBase.toPlainString(), filter.baseAsset());
                targetQty = freeBase;
            } else if (freeBase.signum() == 0) {
                log.warn("[SELL:REJECT] {} free {} = 0 (đã bán thủ công?) - bỏ qua",
                        symbol, filter.baseAsset());
                return;
            }
        }
        BigDecimal qty = FilterUtil.roundQtyDown(targetQty, filter);
        if (qty == null || qty.signum() == 0) {
            log.warn("[SELL:REJECT] {} qty {} không đạt LOT_SIZE (min={}, step={})",
                    symbol, qtyRequested, filter.lotMinQty(), filter.lotStepSize());
            return;
        }

        String clientOrderId = clientOrderId((mode.isFutures() ? "F-S-" : "S-") + type,
                symbol, tickTs);
        log.info("[SELL:SUBMIT:{}] {} qty={}/{} entry={} reason=\"{}\" cid={}",
                type, symbol, qty.toPlainString(), position.qty.toPlainString(),
                position.entryPrice.toPlainString(), reason, clientOrderId);
        long t0 = System.currentTimeMillis();
        OrderGateway.CloseRequest req = mode.isFutures()
                ? OrderGateway.CloseRequest.futuresClose(symbol, qty,
                    position.isLong() ? "LONG" : "SHORT", clientOrderId)
                : OrderGateway.CloseRequest.spotSell(symbol, qty, clientOrderId);
        String resp;
        try {
            resp = gateway.close(req);
        } catch (Exception e) {
            log.error("[SELL:FAIL] {} type={} API lỗi: {}", symbol, type, e.getMessage());
            throw e;
        }
        long latency = System.currentTimeMillis() - t0;
        OrderFillResult fill = parseOrder(resp, symbol, "SELL", mode);
        log.info("[SELL:RESP:{}] {} status={} orderId={} executedQty={} cumQuote={} avg={} latency={}ms",
                type, symbol, fill.status(), fill.orderId(),
                fill.executedQty().toPlainString(),
                fill.cummulativeQuoteQty().toPlainString(),
                fill.avgPrice().toPlainString(), latency);
        logOrderEvent(fill, "SELL", type, reason);

        if (!fill.isFilled() || fill.executedQty().signum() == 0) {
            log.warn("[SELL:NOT_FILLED:{}] {} status={}", type, symbol, fill.status());
            return;
        }
        accountCache.invalidate();

        BigDecimal remaining = position.qty.subtract(fill.executedQty()).max(BigDecimal.ZERO);
        boolean fullyClosed = closesPosition || remaining.signum() == 0;

        // PnL: với spot = (exit-entry)*qty, futures LONG giống vậy, SHORT đổi dấu
        BigDecimal entryValue = position.entryPrice.multiply(fill.executedQty());
        BigDecimal exitValue = fill.cummulativeQuoteQty();
        BigDecimal realizedPnl = position.isLong()
                ? exitValue.subtract(entryValue)
                : entryValue.subtract(exitValue);
        double pnlPct = realizedPnl.divide(entryValue, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        Map<String, Object> tradeRec = new LinkedHashMap<>();
        tradeRec.put("ts", Instant.now().toString());
        tradeRec.put("mode", mode.name());
        tradeRec.put("symbol", symbol);
        tradeRec.put("side", "SELL");
        tradeRec.put("positionSide", position.isLong() ? "LONG" : "SHORT");
        tradeRec.put("type", type);
        tradeRec.put("qty", fill.executedQty().toPlainString());
        tradeRec.put("entryPrice", position.entryPrice.toPlainString());
        tradeRec.put("exitPrice", fill.avgPrice().toPlainString());
        tradeRec.put("leverage", position.leverage);
        tradeRec.put("entryValue", entryValue.toPlainString());
        tradeRec.put("exitValue", exitValue.toPlainString());
        tradeRec.put("realizedPnlUsdt", realizedPnl.setScale(4, RoundingMode.HALF_UP).toPlainString());
        tradeRec.put("pnlPct", String.format("%.4f", pnlPct));
        tradeRec.put("source", position.source);
        tradeRec.put("reason", reason);
        tradeRec.put("fullyClosed", fullyClosed);
        jsonlWriter.append(JsonlWriter.Bucket.TRADES, tradeRec);

        boolean backToReserve = "SCANNER".equals(position.source)
                || "REBALANCE".equals(type);
        double reserveBefore = state.reserveFund;
        if (backToReserve) {
            // Futures: margin đã giải phóng + PnL → ≈ margin + realizedPnl về reserve
            if (mode.isFutures() && position.marginUsdt != null) {
                double marginFraction = fill.executedQty()
                        .divide(position.qty, 8, RoundingMode.HALF_UP)
                        .doubleValue();
                double marginReleased = position.marginUsdt.doubleValue() * marginFraction;
                state.reserveFund += marginReleased + realizedPnl.doubleValue();
            } else {
                state.reserveFund += exitValue.doubleValue();
            }
        }

        if (fullyClosed) {
            state.positions.remove(symbol);
            if ("STOP_LOSS".equals(type) || "KILL_SWITCH".equals(type)) {
                Instant until = Instant.now().plusSeconds(
                        (long) config.exitFor(symbol).cooldownAfterLossHoursV() * 3600);
                state.cooldowns.put(symbol, until);
                log.info("[SELL:COOLDOWN] {} đặt cooldown {}h đến {}",
                        symbol, config.exitFor(symbol).cooldownAfterLossHoursV(), until);
            }
        } else {
            position.qty = remaining;
            if (position.notionalUsdt != null) {
                position.notionalUsdt = remaining.multiply(position.entryPrice);
                if (position.leverage != null && position.leverage > 1) {
                    position.marginUsdt = position.notionalUsdt.divide(
                            BigDecimal.valueOf(position.leverage), 8, RoundingMode.HALF_UP);
                }
            }
        }
        stateStore.save(state);

        log.info("[SELL:DONE:{}] {} PnL={} USDT ({}%) fullyClosed={} reserve {}→{}",
                type, symbol,
                realizedPnl.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                String.format("%+.2f", pnlPct), fullyClosed,
                String.format("%.4f", reserveBefore),
                String.format("%.4f", state.reserveFund));

        NotifyEvent event = switch (type) {
            case "TP_PARTIAL" -> NotifyEvent.PARTIAL_TP;
            case "TP_FULL" -> NotifyEvent.FULL_TP;
            case "STOP_LOSS" -> NotifyEvent.STOP_LOSS;
            case "REBALANCE" -> NotifyEvent.REBALANCE;
            case "KILL_SWITCH" -> NotifyEvent.KILL_SWITCH;
            default -> NotifyEvent.ERROR;
        };
        notifier.send(event, String.format(
                "%s %s qty=%s @ %s → P&L %s USDT (%s%%)",
                type, symbol,
                fill.executedQty().toPlainString(),
                fill.avgPrice().toPlainString(),
                realizedPnl.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                String.format("%.2f", pnlPct)));
    }

    // ===== helpers =====

    private BigDecimal fetchLiquidationPrice(String symbol) {
        if (!(exchangeClient instanceof BinanceFuturesClient fc)) return null;
        try {
            String json = fc.rawPositionRisk(symbol);
            JsonNode arr = MAPPER.readTree(json);
            if (arr.isArray() && arr.size() > 0) {
                String lp = arr.get(0).path("liquidationPrice").asText("0");
                BigDecimal v = new BigDecimal(lp);
                return v.signum() > 0 ? v : null;
            }
        } catch (Exception e) {
            log.warn("Fetch liquidationPrice {} lỗi: {}", symbol, e.getMessage());
        }
        return null;
    }

    private void logOrderEvent(OrderFillResult fill, String side, String type, String reason) {
        jsonlWriter.append(JsonlWriter.Bucket.ORDERS, new LinkedHashMap<>() {{
            put("ts", Instant.now().toString());
            put("symbol", fill.symbol());
            put("side", side);
            put("type", type);
            put("clientOrderId", fill.clientOrderId());
            put("orderId", fill.orderId());
            put("status", fill.status());
            put("executedQty", fill.executedQty().toPlainString());
            put("cummulativeQuoteQty", fill.cummulativeQuoteQty().toPlainString());
            put("avgPrice", fill.avgPrice().toPlainString());
            put("reason", reason);
        }});
    }

    private OrderFillResult parseOrder(String json, String symbol, String side, ExchangeMode mode) {
        try {
            JsonNode root = MAPPER.readTree(json);
            BigDecimal executed = new BigDecimal(root.path("executedQty").asText("0"));
            BigDecimal cumQuote;
            BigDecimal avg;
            if (mode.isFutures()) {
                // Futures có "cumQuote" (ko phải "cummulativeQuoteQty") và "avgPrice" trực tiếp
                cumQuote = new BigDecimal(root.path("cumQuote").asText(
                        root.path("cummulativeQuoteQty").asText("0")));
                String avgStr = root.path("avgPrice").asText("0");
                avg = new BigDecimal(avgStr);
                if (avg.signum() == 0 && executed.signum() > 0) {
                    avg = cumQuote.divide(executed, 8, RoundingMode.HALF_UP);
                }
                // Nếu cumQuote = 0 nhưng có executedQty + avgPrice, tự tính
                if (cumQuote.signum() == 0 && executed.signum() > 0 && avg.signum() > 0) {
                    cumQuote = executed.multiply(avg);
                }
            } else {
                cumQuote = new BigDecimal(root.path("cummulativeQuoteQty").asText("0"));
                avg = executed.signum() > 0
                        ? cumQuote.divide(executed, 8, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            }
            String status = root.path("status").asText("UNKNOWN");
            // Futures MARKET đôi khi trả "NEW" (đợi fill). Coi NEW+executedQty>0 là FILLED.
            if ("NEW".equals(status) && executed.signum() > 0) status = "FILLED";
            return new OrderFillResult(
                    symbol, side, status,
                    root.path("clientOrderId").asText(""),
                    root.path("orderId").asLong(0),
                    executed, cumQuote, avg);
        } catch (Exception e) {
            log.error("Parse order response lỗi: {}", json, e);
            return new OrderFillResult(symbol, side, "PARSE_ERROR",
                    "", 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private String clientOrderId(String type, String symbol, long tickTs) {
        String raw = type + "-" + symbol + "-" + tickTs;
        if (raw.length() > 36) raw = raw.substring(0, 36);
        return raw.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
