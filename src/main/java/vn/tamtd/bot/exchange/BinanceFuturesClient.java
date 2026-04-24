package vn.tamtd.bot.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binance USDⓈ-M Futures client. SDK binance-connector-java:3.4.1 không hỗ trợ futures,
 * nên tự gọi HTTP bằng OkHttp + HMAC-SHA256 signing.
 *
 * <p>Endpoint: {@code https://fapi.binance.com/fapi/v1/*} (hoặc testnet {@code testnet.binancefuture.com}).
 *
 * <p>Weight pool riêng 2400/phút cho REST (khác spot). Dùng chung {@link RateLimiter}
 * với weight = trong docs Binance (ví dụ /fapi/v1/klines = 5).
 */
public final class BinanceFuturesClient implements ExchangeClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceFuturesClient.class);
    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String URL_LIVE = "https://fapi.binance.com";
    private static final String URL_TESTNET = "https://testnet.binancefuture.com";

    private final OkHttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String apiSecret;
    private final RateLimiter rateLimiter;
    private final int recvWindow;

    public BinanceFuturesClient(AppConfig config, RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        this.recvWindow = Math.max(1000, config.exchange().recvWindow());
        this.baseUrl = config.exchange().useTestnet() ? URL_TESTNET : URL_LIVE;
        this.apiKey = config.secrets() == null ? null : config.secrets().binanceApiKey();
        this.apiSecret = config.secrets() == null ? null : config.secrets().binanceApiSecret();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15))
                .build();
        log.info("BinanceFuturesClient init: baseUrl={}, hasKey={}",
                baseUrl, apiKey != null && !apiKey.isEmpty());
    }

    @Override public String baseUrl() { return baseUrl; }

    // ==== Public market data ====

    @Override public String rawExchangeInfo() {
        return getPublic("/fapi/v1/exchangeInfo", Map.of(), 1);
    }
    @Override public String rawServerTime() {
        return getPublic("/fapi/v1/time", Map.of(), 1);
    }
    @Override public String rawAll24hTicker() {
        return getPublic("/fapi/v1/ticker/24hr", Map.of(), 40);
    }
    @Override public String raw24hTicker(String symbol) {
        return getPublic("/fapi/v1/ticker/24hr", Map.of("symbol", symbol), 1);
    }
    @Override public String rawKlines(String symbol, String interval, int limit) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("symbol", symbol);
        p.put("interval", interval);
        p.put("limit", limit);
        return getPublic("/fapi/v1/klines", p, 5);
    }
    @Override public BigDecimal latestPrice(String symbol) {
        String json = getPublic("/fapi/v1/ticker/price", Map.of("symbol", symbol), 1);
        try {
            JsonNode root = MAPPER.readTree(json);
            return new BigDecimal(root.get("price").asText());
        } catch (Exception e) {
            throw new RuntimeException("Parse futures latestPrice lỗi " + symbol, e);
        }
    }

    /** Mark price + funding rate. */
    public String rawPremiumIndex(String symbol) {
        return getPublic("/fapi/v1/premiumIndex", Map.of("symbol", symbol), 1);
    }

    public BigDecimal markPrice(String symbol) {
        try {
            JsonNode root = MAPPER.readTree(rawPremiumIndex(symbol));
            return new BigDecimal(root.get("markPrice").asText());
        } catch (Exception e) {
            throw new RuntimeException("Parse markPrice lỗi " + symbol, e);
        }
    }

    /** Funding rate hiện tại (mỗi 8h). Dương = long trả short; âm = short trả long. */
    public BigDecimal currentFundingRate(String symbol) {
        try {
            JsonNode root = MAPPER.readTree(rawPremiumIndex(symbol));
            return new BigDecimal(root.get("lastFundingRate").asText());
        } catch (Exception e) {
            throw new RuntimeException("Parse fundingRate lỗi " + symbol, e);
        }
    }

    // ==== Account (signed) ====

    @Override public String rawAccount() {
        return getSigned("/fapi/v2/account", Map.of(), 5);
    }
    @Override public String rawOpenOrders(String symbol) {
        Map<String, Object> p = symbol == null ? Map.of() : Map.of("symbol", symbol);
        return getSigned("/fapi/v1/openOrders", p, symbol == null ? 40 : 1);
    }

    /** positionRisk - liquidation price, unrealized PnL, leverage/marginType hiện tại. */
    public String rawPositionRisk(String symbol) {
        Map<String, Object> p = symbol == null ? Map.of() : Map.of("symbol", symbol);
        return getSigned("/fapi/v2/positionRisk", p, 5);
    }

    // ==== Trade (signed, package-private cho FuturesOrderGateway) ====

    String postSigned(String path, Map<String, Object> params, int weight, boolean orderOp) {
        if (orderOp) rateLimiter.acquireOrder();
        rateLimiter.acquireWeight(weight);
        return executeWithRetry(() -> {
            Map<String, Object> p = new LinkedHashMap<>(params);
            p.put("timestamp", System.currentTimeMillis());
            p.put("recvWindow", recvWindow);
            String body = BinanceHttpSigner.buildQuery(p);
            String signature = BinanceHttpSigner.hmacSha256Hex(body, apiSecret);
            body = body + "&signature=" + signature;
            Request req = new Request.Builder()
                    .url(baseUrl + path)
                    .addHeader("X-MBX-APIKEY", apiKey)
                    .post(RequestBody.create(body, FORM))
                    .build();
            return execute(req);
        });
    }

    String deleteSigned(String path, Map<String, Object> params, int weight) {
        rateLimiter.acquireWeight(weight);
        return executeWithRetry(() -> {
            Map<String, Object> p = new LinkedHashMap<>(params);
            p.put("timestamp", System.currentTimeMillis());
            p.put("recvWindow", recvWindow);
            String qs = BinanceHttpSigner.buildQuery(p);
            String signature = BinanceHttpSigner.hmacSha256Hex(qs, apiSecret);
            HttpUrl url = HttpUrl.parse(baseUrl + path + "?" + qs + "&signature=" + signature);
            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("X-MBX-APIKEY", apiKey)
                    .delete()
                    .build();
            return execute(req);
        });
    }

    // ==== Internals ====

    private String getPublic(String path, Map<String, Object> params, int weight) {
        rateLimiter.acquireWeight(weight);
        return executeWithRetry(() -> {
            HttpUrl.Builder b = HttpUrl.parse(baseUrl + path).newBuilder();
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (e.getValue() != null) b.addQueryParameter(e.getKey(), String.valueOf(e.getValue()));
            }
            Request req = new Request.Builder().url(b.build()).get().build();
            return execute(req);
        });
    }

    private String getSigned(String path, Map<String, Object> params, int weight) {
        rateLimiter.acquireWeight(weight);
        return executeWithRetry(() -> {
            Map<String, Object> p = new LinkedHashMap<>(params);
            p.put("timestamp", System.currentTimeMillis());
            p.put("recvWindow", recvWindow);
            String qs = BinanceHttpSigner.buildQuery(p);
            String signature = BinanceHttpSigner.hmacSha256Hex(qs, apiSecret);
            Request req = new Request.Builder()
                    .url(baseUrl + path + "?" + qs + "&signature=" + signature)
                    .addHeader("X-MBX-APIKEY", apiKey)
                    .get()
                    .build();
            return execute(req);
        });
    }

    private String execute(Request req) throws IOException {
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() == null ? "" : resp.body().string();
            int code = resp.code();
            if (code == 429 || code == 418) {
                throw new RuntimeException("HTTP " + code + " rate limit: " + body);
            }
            if (code >= 500) {
                throw new RuntimeException("HTTP " + code + " server error: " + body);
            }
            if (!resp.isSuccessful()) {
                throw new RuntimeException("HTTP " + code + " " + req.url() + " body=" + body);
            }
            return body;
        }
    }

    private String executeWithRetry(HttpCall call) {
        int attempt = 0;
        long backoff = 500;
        while (true) {
            try {
                return call.exec();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if ((msg.contains("429") || msg.contains("418")) && attempt < 5) {
                    log.warn("Futures rate limit (attempt {}): {}", attempt + 1, msg);
                    rateLimiter.penaltyWait(backoff);
                    attempt++;
                    backoff = Math.min(backoff * 2, 10_000);
                } else if ((msg.contains("HTTP 5") || msg.contains("server error")) && attempt < 3) {
                    log.warn("Futures 5xx (attempt {}): {}", attempt + 1, msg);
                    sleep(backoff);
                    attempt++;
                    backoff = Math.min(backoff * 2, 5_000);
                } else if (e instanceof RuntimeException re) {
                    throw re;
                } else {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @FunctionalInterface
    private interface HttpCall { String exec() throws Exception; }
}
