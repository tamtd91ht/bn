package vn.tamtd.bot.exchange;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Giao diện chung cho Spot và USDⓈ-M Futures.
 *
 * <p>Các endpoint public (klines, 24h ticker, exchangeInfo, serverTime) + account snapshot.
 * Order endpoint được tách ra {@link OrderGateway} để futures có thêm leverage/marginType/reduceOnly.
 *
 * <p>Method {@code raw*} trả về JSON thô - caller tự parse bằng Jackson, giữ signature gọn.
 */
public interface ExchangeClient {

    /** {@code "api.binance.com"} hoặc {@code "fapi.binance.com"} (log debug). */
    String baseUrl();

    // ==== Public market data ====

    String rawExchangeInfo();

    String rawServerTime();

    /** ticker 24h cho toàn sàn (weight cao). */
    String rawAll24hTicker();

    /** ticker 24h cho 1 symbol. */
    String raw24hTicker(String symbol);

    /** klines historical cho 1 symbol. */
    String rawKlines(String symbol, String interval, int limit);

    /** Giá hiện tại (gần nhất). */
    BigDecimal latestPrice(String symbol);

    /**
     * Giá hiện tại cho nhiều symbol trong 1 request (batch) - tiết kiệm số request & weight
     * so với gọi {@link #latestPrice} từng symbol.
     *
     * <p>Default: fallback gọi {@link #latestPrice} từng symbol (cho client chưa hỗ trợ batch).
     * {@link BinanceSpotClient} override bằng endpoint {@code /api/v3/ticker/price?symbols=[...]}.
     */
    default Map<String, BigDecimal> latestPrices(Collection<String> symbols) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (String s : symbols) {
            out.put(s, latestPrice(s));
        }
        return out;
    }

    // ==== Account (signed) ====

    /**
     * Snapshot account.
     * <ul>
     *   <li>Spot: response có {@code balances[]} (free, locked)</li>
     *   <li>Futures: response có {@code assets[]} (walletBalance, marginBalance) + {@code positions[]}</li>
     * </ul>
     */
    String rawAccount();

    String rawOpenOrders(String symbol);
}
