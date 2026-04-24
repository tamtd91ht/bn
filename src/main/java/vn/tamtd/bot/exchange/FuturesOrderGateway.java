package vn.tamtd.bot.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link OrderGateway} cho USDⓈ-M Futures.
 *
 * <p>Endpoint chính:
 * <ul>
 *   <li>{@code POST /fapi/v1/order} - đặt lệnh MARKET</li>
 *   <li>{@code POST /fapi/v1/leverage} - đặt leverage (1 lần/symbol)</li>
 *   <li>{@code POST /fapi/v1/marginType} - ISOLATED/CROSSED (1 lần/symbol)</li>
 * </ul>
 *
 * <p>Trước khi openLong/openShort phải gọi {@link #setLeverage} và {@link #setMarginType}
 * (nhưng ta cache để chỉ gọi 1 lần/symbol để tránh "No need to change" error).
 *
 * <p>Close: MARKET với {@code reduceOnly=true} để đảm bảo chỉ đóng vị thế, không mở ngược chiều.
 */
public final class FuturesOrderGateway implements OrderGateway {

    private static final Logger log = LoggerFactory.getLogger(FuturesOrderGateway.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BinanceFuturesClient client;
    private final AppConfig config;

    // Cache để không spam setLeverage/setMarginType mỗi order
    private final Map<String, Integer> leverageApplied = new ConcurrentHashMap<>();
    private final Map<String, String> marginTypeApplied = new ConcurrentHashMap<>();

    public FuturesOrderGateway(BinanceFuturesClient client, AppConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public String openLong(OpenRequest r) {
        return placeMarketOpen(r, "BUY", "LONG");
    }

    @Override
    public String openShort(OpenRequest r) {
        return placeMarketOpen(r, "SELL", "SHORT");
    }

    @Override
    public String close(CloseRequest r) {
        // LONG → đóng bằng SELL, SHORT → đóng bằng BUY (theo quy ước vị thế futures USDⓈ-M)
        String side = "LONG".equalsIgnoreCase(r.side()) ? "SELL" : "BUY";
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("symbol", r.symbol());
        p.put("side", side);
        p.put("type", "MARKET");
        p.put("quantity", r.qty().toPlainString());
        if (r.reduceOnly()) p.put("reduceOnly", "true");
        // positionSide chỉ cần khi hedge mode; ONE_WAY không gửi
        if ("HEDGE".equalsIgnoreCase(config.exchange().positionMode())) {
            p.put("positionSide", r.side().toUpperCase());
        }
        if (r.clientOrderId() != null) p.put("newClientOrderId", r.clientOrderId());
        log.info("[API:FUT:CLOSE] {} side={} qty={} reduceOnly={} cid={}",
                r.symbol(), side, r.qty().toPlainString(), r.reduceOnly(), r.clientOrderId());
        return client.postSigned("/fapi/v1/order", p, 1, true);
    }

    @Override
    public void setLeverage(String symbol, int leverage) {
        Integer prev = leverageApplied.get(symbol);
        if (prev != null && prev == leverage) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("symbol", symbol);
        p.put("leverage", leverage);
        try {
            client.postSigned("/fapi/v1/leverage", p, 1, false);
            leverageApplied.put(symbol, leverage);
            log.info("[FUT:SET_LEV] {} leverage={}", symbol, leverage);
        } catch (Exception e) {
            log.warn("[FUT:SET_LEV:FAIL] {} → {}: {}", symbol, leverage, e.getMessage());
        }
    }

    @Override
    public void setMarginType(String symbol, String marginType) {
        String prev = marginTypeApplied.get(symbol);
        if (prev != null && prev.equalsIgnoreCase(marginType)) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("symbol", symbol);
        p.put("marginType", marginType.toUpperCase());
        try {
            client.postSigned("/fapi/v1/marginType", p, 1, false);
            marginTypeApplied.put(symbol, marginType);
            log.info("[FUT:SET_MARGIN] {} marginType={}", symbol, marginType);
        } catch (Exception e) {
            // Binance trả lỗi "No need to change margin type" khi đã đặt rồi - bỏ qua an toàn
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("-4046") || msg.contains("No need to change")) {
                marginTypeApplied.put(symbol, marginType);
            } else {
                log.warn("[FUT:SET_MARGIN:FAIL] {} → {}: {}", symbol, marginType, e.getMessage());
            }
        }
    }

    private String placeMarketOpen(OpenRequest r, String side, String positionSide) {
        int leverage = config.leverageFor(r.symbol());
        setMarginType(r.symbol(), config.exchange().marginType());
        setLeverage(r.symbol(), leverage);

        BigDecimal qty = r.qty();
        if (qty == null) {
            // futures không có quoteOrderQty - phải tự tính qty từ quoteAmount × leverage / markPrice
            BigDecimal notional = r.quoteAmountUsdt().multiply(BigDecimal.valueOf(leverage));
            BigDecimal price = client.markPrice(r.symbol());
            if (price.signum() == 0) {
                throw new RuntimeException("Mark price = 0 cho " + r.symbol());
            }
            qty = notional.divide(price, 8, RoundingMode.DOWN);
        }

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("symbol", r.symbol());
        p.put("side", side);
        p.put("type", "MARKET");
        p.put("quantity", qty.toPlainString());
        if ("HEDGE".equalsIgnoreCase(config.exchange().positionMode())) {
            p.put("positionSide", positionSide);
        }
        if (r.clientOrderId() != null) p.put("newClientOrderId", r.clientOrderId());

        log.info("[API:FUT:OPEN] {} side={} positionSide={} qty={} lev={} cid={}",
                r.symbol(), side, positionSide, qty.toPlainString(), leverage, r.clientOrderId());
        String resp = client.postSigned("/fapi/v1/order", p, 1, true);
        // Enrich response với avgPrice từ markPrice nếu thiếu (MARKET response có "avgPrice")
        return ensureAvgPrice(resp);
    }

    private static String ensureAvgPrice(String json) {
        // Futures MARKET order response có sẵn avgPrice; nhưng nếu 0 (chưa fill báo cáo) ta
        // không tính được. Giữ nguyên để caller parse.
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.has("avgPrice") && !"0".equals(root.get("avgPrice").asText())
                    && !"0.00000".equals(root.get("avgPrice").asText())) {
                return json;
            }
        } catch (Exception ignored) {}
        return json;
    }
}
