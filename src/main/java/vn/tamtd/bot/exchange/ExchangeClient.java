package vn.tamtd.bot.exchange;

import java.math.BigDecimal;

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
