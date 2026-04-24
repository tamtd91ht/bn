package vn.tamtd.bot.exchange;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.SpotClientImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;

import java.math.BigDecimal;
import java.util.LinkedHashMap;

/**
 * Binance Spot — dùng SDK binance-connector-java.
 * Wrap {@link SpotClient} với rate limiter + retry 429/5xx.
 * Implements {@link ExchangeClient}, làm data source cho Spot mode.
 * Order logic tách ra {@link SpotOrderGateway}.
 */
public final class BinanceSpotClient implements ExchangeClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceSpotClient.class);

    private static final String URL_LIVE = "https://api.binance.com";
    private static final String URL_TESTNET = "https://testnet.binance.vision";

    private final SpotClient spotClient;
    private final RateLimiter rateLimiter;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public BinanceSpotClient(AppConfig config, RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.baseUrl = config.exchange().useTestnet() ? URL_TESTNET : URL_LIVE;
        String apiKey = config.secrets() == null ? null : config.secrets().binanceApiKey();
        String apiSecret = config.secrets() == null ? null : config.secrets().binanceApiSecret();
        boolean hasKeys = apiKey != null && !apiKey.isEmpty()
                && apiSecret != null && !apiSecret.isEmpty();
        this.spotClient = hasKeys
                ? new SpotClientImpl(apiKey, apiSecret, baseUrl)
                : new SpotClientImpl(baseUrl);
        log.info("BinanceSpotClient init: baseUrl={}, hasKey={}", baseUrl, hasKeys);
    }

    @Override
    public String baseUrl() { return baseUrl; }

    SpotClient rawSdk() { return spotClient; }
    RateLimiter rateLimiter() { return rateLimiter; }

    // ==== Market data ====

    @Override
    public String rawExchangeInfo() {
        return executeWeighted(20, () -> spotClient.createMarket().exchangeInfo(new LinkedHashMap<>()));
    }

    @Override
    public String rawServerTime() {
        return executeWeighted(1, () -> spotClient.createMarket().time());
    }

    @Override
    public String raw24hTicker(String symbol) {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        return executeWeighted(2, () -> spotClient.createMarket().ticker24H(params));
    }

    @Override
    public String rawAll24hTicker() {
        return executeWeighted(40, () -> spotClient.createMarket().ticker24H(new LinkedHashMap<>()));
    }

    @Override
    public String rawKlines(String symbol, String interval, int limit) {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("interval", interval);
        params.put("limit", limit);
        return executeWeighted(2, () -> spotClient.createMarket().klines(params));
    }

    @Override
    public BigDecimal latestPrice(String symbol) {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        String json = executeWeighted(2, () -> spotClient.createMarket().tickerSymbol(params));
        try {
            JsonNode root = mapper.readTree(json);
            return new BigDecimal(root.get("price").asText());
        } catch (Exception e) {
            throw new RuntimeException("Parse latestPrice lỗi cho " + symbol, e);
        }
    }

    // ==== Account ====

    @Override
    public String rawAccount() {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        return executeWeighted(20, () -> spotClient.createTrade().account(params));
    }

    @Override
    public String rawOpenOrders(String symbol) {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        if (symbol != null) params.put("symbol", symbol);
        return executeWeighted(symbol == null ? 40 : 6,
                () -> spotClient.createTrade().getOpenOrders(params));
    }

    // ==== Trade (package-private, dùng từ SpotOrderGateway) ====

    String marketBuyByQuote(String symbol, BigDecimal quoteOrderQty, String clientOrderId) {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", "BUY");
        params.put("type", "MARKET");
        params.put("quoteOrderQty", quoteOrderQty.toPlainString());
        if (clientOrderId != null) params.put("newClientOrderId", clientOrderId);
        log.info("[API:BUY] MARKET BUY {} quoteQty={} clientOrderId={}",
                symbol, quoteOrderQty.toPlainString(), clientOrderId);
        rateLimiter.acquireOrder();
        return executeWeighted(1, () -> spotClient.createTrade().newOrder(params));
    }

    String marketSellByQty(String symbol, BigDecimal qty, String clientOrderId) {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", "SELL");
        params.put("type", "MARKET");
        params.put("quantity", qty.toPlainString());
        if (clientOrderId != null) params.put("newClientOrderId", clientOrderId);
        log.info("[API:SELL] MARKET SELL {} qty={} clientOrderId={}",
                symbol, qty.toPlainString(), clientOrderId);
        rateLimiter.acquireOrder();
        return executeWeighted(1, () -> spotClient.createTrade().newOrder(params));
    }

    private String executeWeighted(int weight, BinanceCall call) {
        rateLimiter.acquireWeight(weight);
        int attempt = 0;
        long backoff = 500;
        while (true) {
            try {
                return call.exec();
            } catch (RuntimeException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if ((msg.contains("429") || msg.contains("418")) && attempt < 5) {
                    log.warn("Binance rate limit (attempt {}): {}", attempt + 1, msg);
                    rateLimiter.penaltyWait(backoff);
                    attempt++;
                    backoff = Math.min(backoff * 2, 10_000);
                } else if (attempt < 3 && is5xx(msg)) {
                    log.warn("Binance 5xx (attempt {}): {}", attempt + 1, msg);
                    sleep(backoff);
                    attempt++;
                    backoff = Math.min(backoff * 2, 5_000);
                } else {
                    throw e;
                }
            }
        }
    }

    private static boolean is5xx(String msg) {
        return msg.contains("500") || msg.contains("502") || msg.contains("503")
                || msg.contains("504");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Thread interrupted trong sleep retry", e);
        }
    }

    @FunctionalInterface
    private interface BinanceCall {
        String exec();
    }
}
