package vn.tamtd.bot.exchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.ExchangeMode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Cache {@link SymbolFilter} cho tất cả symbol Binance (Spot hoặc Futures).
 * Refresh tự động mỗi giờ.
 *
 * <p>Spot lấy từ {@code /api/v3/exchangeInfo}, futures {@code /fapi/v1/exchangeInfo}.
 * Cấu trúc JSON cơ bản giống nhau (symbols[].filters[]), chỉ khác field permission.
 */
public final class FilterCache {

    private static final Logger log = LoggerFactory.getLogger(FilterCache.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofHours(1);

    private final ExchangeClient client;
    private final ExchangeMode mode;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Map<String, SymbolFilter> filters = Map.of();
    private volatile Instant lastRefresh = Instant.EPOCH;

    public FilterCache(ExchangeClient client, ExchangeMode mode) {
        this.client = client;
        this.mode = mode;
    }

    public SymbolFilter get(String symbol) {
        ensureFresh();
        return filters.get(symbol);
    }

    public boolean contains(String symbol) {
        ensureFresh();
        return filters.containsKey(symbol);
    }

    public synchronized void refresh() {
        try {
            String json = client.rawExchangeInfo();
            JsonNode root = mapper.readTree(json);
            Map<String, SymbolFilter> map = new HashMap<>();
            for (JsonNode sym : root.get("symbols")) {
                String status = sym.path("status").asText("");
                if (!"TRADING".equals(status)) continue;
                if (mode == ExchangeMode.SPOT && !isSpotAllowed(sym)) continue;
                SymbolFilter f = parseSymbol(sym);
                if (f != null) map.put(f.symbol(), f);
            }
            this.filters = Map.copyOf(map);
            this.lastRefresh = Instant.now();
            log.info("FilterCache ({}) refreshed: {} symbols", mode, filters.size());
        } catch (Exception e) {
            log.error("Refresh exchangeInfo ({}) lỗi", mode, e);
        }
    }

    private void ensureFresh() {
        if (Duration.between(lastRefresh, Instant.now()).compareTo(REFRESH_INTERVAL) > 0) {
            refresh();
        }
    }

    private static boolean isSpotAllowed(JsonNode sym) {
        if (sym.path("isSpotTradingAllowed").asBoolean(false)) return true;
        JsonNode perms = sym.path("permissions");
        if (perms.isArray()) {
            for (JsonNode p : perms) {
                if ("SPOT".equalsIgnoreCase(p.asText())) return true;
            }
        }
        return false;
    }

    private SymbolFilter parseSymbol(JsonNode sym) {
        try {
            String symbol = sym.get("symbol").asText();
            String base = sym.get("baseAsset").asText();
            String quote = sym.get("quoteAsset").asText();
            int basePrec = sym.path("baseAssetPrecision").asInt(
                    sym.path("quantityPrecision").asInt(8));
            int quotePrec = sym.path("quoteAssetPrecision").asInt(
                    sym.path("quotePrecision").asInt(8));

            BigDecimal lotMin = null, lotMax = null, lotStep = null;
            BigDecimal priceMin = null, priceMax = null, priceTick = null;
            BigDecimal minNotional = BigDecimal.ZERO;

            for (JsonNode filter : sym.get("filters")) {
                String type = filter.get("filterType").asText();
                switch (type) {
                    case "LOT_SIZE", "MARKET_LOT_SIZE" -> {
                        if (lotMin == null) {
                            lotMin = new BigDecimal(filter.get("minQty").asText());
                            lotMax = new BigDecimal(filter.get("maxQty").asText());
                            lotStep = new BigDecimal(filter.get("stepSize").asText());
                        }
                    }
                    case "PRICE_FILTER" -> {
                        priceMin = new BigDecimal(filter.get("minPrice").asText());
                        priceMax = new BigDecimal(filter.get("maxPrice").asText());
                        priceTick = new BigDecimal(filter.get("tickSize").asText());
                    }
                    case "NOTIONAL", "MIN_NOTIONAL" -> {
                        if (filter.has("minNotional")) {
                            BigDecimal mn = new BigDecimal(filter.get("minNotional").asText());
                            if (minNotional.signum() == 0) minNotional = mn;
                        } else if (filter.has("notional")) {
                            // Futures dùng "notional"
                            BigDecimal mn = new BigDecimal(filter.get("notional").asText());
                            if (minNotional.signum() == 0) minNotional = mn;
                        }
                    }
                    default -> {}
                }
            }
            if (lotMin == null || priceMin == null) return null;
            return new SymbolFilter(symbol, base, quote, basePrec, quotePrec,
                    lotMin, lotMax, lotStep, priceMin, priceMax, priceTick, minNotional);
        } catch (Exception e) {
            log.warn("Parse filter lỗi cho symbol {}", sym.path("symbol").asText(""), e);
            return null;
        }
    }
}
