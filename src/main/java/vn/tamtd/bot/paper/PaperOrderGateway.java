package vn.tamtd.bot.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.exchange.ExchangeClient;
import vn.tamtd.bot.exchange.FilterCache;
import vn.tamtd.bot.exchange.OrderGateway;
import vn.tamtd.bot.exchange.SymbolFilter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link OrderGateway} mô phỏng cho paper-trade (dry-run). KHÔNG gọi Binance trade endpoint.
 *
 * <p>Fill ảo:
 * <ul>
 *   <li>Lấy giá real-time từ {@link ExchangeClient#latestPrice(String)} (public, không tốn quota signed).</li>
 *   <li>Áp slippage: BUY nhân (1 + slippage%), SELL nhân (1 - slippage%).</li>
 *   <li>Áp fee {@code feeRate} (mặc định 0.1% taker) — trừ vào qty nhận được khi BUY,
 *       hoặc trừ vào USDT nhận được khi SELL.</li>
 *   <li>Cập nhật {@link PaperAccount}.</li>
 *   <li>Trả JSON format giống Binance Spot {@code newOrder} response để
 *       {@link vn.tamtd.bot.executor.OrderExecutor} parse không cần đổi.</li>
 * </ul>
 *
 * <p>Chỉ hỗ trợ Spot (mode SPOT). Futures paper không được implement ở đây.
 */
public final class PaperOrderGateway implements OrderGateway {

    private static final Logger log = LoggerFactory.getLogger(PaperOrderGateway.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConfigRegistry configRegistry;
    private final ExchangeClient exchangeClient;
    private final FilterCache filterCache;
    private final PaperAccount paperAccount;
    private final AtomicLong orderIdSeq = new AtomicLong(System.currentTimeMillis() / 1000L);

    public PaperOrderGateway(ConfigRegistry configRegistry,
                             ExchangeClient exchangeClient,
                             FilterCache filterCache,
                             PaperAccount paperAccount) {
        this.configRegistry = configRegistry;
        this.exchangeClient = exchangeClient;
        this.filterCache = filterCache;
        this.paperAccount = paperAccount;
    }

    @Override
    public String openLong(OpenRequest r) {
        AppConfig.PaperTrade pt = configRegistry.current().paperTrade();
        SymbolFilter filter = filterCache.get(r.symbol());
        if (filter == null) {
            log.warn("[PAPER:BUY:REJECT] {} không có filter", r.symbol());
            return mockEmptyResponse(r.symbol(), r.clientOrderId());
        }
        if (r.quoteAmountUsdt() == null) {
            throw new IllegalArgumentException("Paper Spot openLong cần quoteAmountUsdt");
        }

        BigDecimal ticker = exchangeClient.latestPrice(r.symbol());
        BigDecimal slipMul = BigDecimal.ONE.add(BigDecimal.valueOf(pt.slippagePctV() / 100.0));
        BigDecimal fillPrice = ticker.multiply(slipMul).setScale(8, RoundingMode.HALF_UP);

        BigDecimal usdtSpent = r.quoteAmountUsdt();
        BigDecimal qtyGross = usdtSpent.divide(fillPrice, 12, RoundingMode.DOWN);
        BigDecimal feeMul = BigDecimal.ONE.subtract(BigDecimal.valueOf(pt.feeRateV()));
        BigDecimal qtyAfterFee = qtyGross.multiply(feeMul);
        // Round xuống step
        if (filter.lotStepSize().signum() > 0) {
            BigDecimal steps = qtyAfterFee.divide(filter.lotStepSize(), 0, RoundingMode.DOWN);
            qtyAfterFee = steps.multiply(filter.lotStepSize());
        }
        if (qtyAfterFee.signum() <= 0) {
            log.warn("[PAPER:BUY:REJECT] {} qty sau fee/round = 0 (usdt={}, price={})",
                    r.symbol(), usdtSpent.toPlainString(), fillPrice.toPlainString());
            return mockEmptyResponse(r.symbol(), r.clientOrderId());
        }

        // Cập nhật ví ảo
        paperAccount.applyBuy(filter.baseAsset(), usdtSpent, qtyAfterFee);
        long orderId = orderIdSeq.incrementAndGet();
        log.info("[PAPER:BUY] {} ticker={} fillPrice={} (+{}% slip) usdt={} qty={} (fee={}) cid={} oid={}",
                r.symbol(), ticker.toPlainString(), fillPrice.toPlainString(),
                pt.slippagePctV(), usdtSpent.toPlainString(), qtyAfterFee.toPlainString(),
                pt.feeRateV(), r.clientOrderId(), orderId);
        return mockSpotResponse(r.symbol(), r.clientOrderId(), orderId,
                "BUY", qtyAfterFee, usdtSpent, fillPrice);
    }

    @Override
    public String openShort(OpenRequest request) {
        throw new UnsupportedOperationException("Paper-trade chưa hỗ trợ SHORT (chỉ Spot LONG)");
    }

    @Override
    public String close(CloseRequest r) {
        AppConfig.PaperTrade pt = configRegistry.current().paperTrade();
        SymbolFilter filter = filterCache.get(r.symbol());
        if (filter == null) {
            log.warn("[PAPER:SELL:REJECT] {} không có filter", r.symbol());
            return mockEmptyResponse(r.symbol(), r.clientOrderId());
        }
        BigDecimal ticker = exchangeClient.latestPrice(r.symbol());
        BigDecimal slipMul = BigDecimal.ONE.subtract(BigDecimal.valueOf(pt.slippagePctV() / 100.0));
        BigDecimal fillPrice = ticker.multiply(slipMul).setScale(8, RoundingMode.HALF_UP);

        BigDecimal qty = r.qty();
        BigDecimal usdtGross = qty.multiply(fillPrice);
        BigDecimal feeMul = BigDecimal.ONE.subtract(BigDecimal.valueOf(pt.feeRateV()));
        BigDecimal usdtAfterFee = usdtGross.multiply(feeMul)
                .setScale(filter.quoteAssetPrecision(), RoundingMode.DOWN);

        paperAccount.applySell(filter.baseAsset(), qty, usdtAfterFee);
        long orderId = orderIdSeq.incrementAndGet();
        log.info("[PAPER:SELL] {} ticker={} fillPrice={} (-{}% slip) qty={} usdt={} (fee={}) cid={} oid={}",
                r.symbol(), ticker.toPlainString(), fillPrice.toPlainString(),
                pt.slippagePctV(), qty.toPlainString(), usdtAfterFee.toPlainString(),
                pt.feeRateV(), r.clientOrderId(), orderId);
        return mockSpotResponse(r.symbol(), r.clientOrderId(), orderId,
                "SELL", qty, usdtAfterFee, fillPrice);
    }

    @Override
    public void setLeverage(String symbol, int leverage) {
        // no-op cho paper Spot
    }

    @Override
    public void setMarginType(String symbol, String marginType) {
        // no-op cho paper Spot
    }

    private String mockSpotResponse(String symbol, String clientOrderId, long orderId,
                                    String side, BigDecimal executedQty,
                                    BigDecimal cumQuote, BigDecimal avgPrice) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("symbol", symbol);
        resp.put("orderId", orderId);
        resp.put("clientOrderId", clientOrderId == null ? "" : clientOrderId);
        resp.put("transactTime", System.currentTimeMillis());
        resp.put("price", "0.00000000");
        resp.put("origQty", executedQty.toPlainString());
        resp.put("executedQty", executedQty.toPlainString());
        resp.put("cummulativeQuoteQty", cumQuote.toPlainString());
        resp.put("status", "FILLED");
        resp.put("timeInForce", "GTC");
        resp.put("type", "MARKET");
        resp.put("side", side);
        resp.put("isPaperTrade", true);
        try {
            return MAPPER.writeValueAsString(resp);
        } catch (Exception e) {
            throw new RuntimeException("Paper response serialize lỗi", e);
        }
    }

    private String mockEmptyResponse(String symbol, String clientOrderId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("symbol", symbol);
        resp.put("orderId", 0);
        resp.put("clientOrderId", clientOrderId == null ? "" : clientOrderId);
        resp.put("status", "REJECTED");
        resp.put("executedQty", "0");
        resp.put("cummulativeQuoteQty", "0");
        resp.put("isPaperTrade", true);
        try {
            return MAPPER.writeValueAsString(resp);
        } catch (Exception e) {
            throw new RuntimeException("Paper empty response serialize lỗi", e);
        }
    }
}
