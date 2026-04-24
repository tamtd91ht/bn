package vn.tamtd.bot.exchange;

import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ExchangeMode;

/**
 * Tạo {@link ExchangeClient} + {@link OrderGateway} phù hợp theo config.mode.
 * Wire tại {@link vn.tamtd.bot.app.RunCommand} và các command cần gọi API.
 */
public final class ExchangeFactory {

    private ExchangeFactory() {}

    public record Pair(ExchangeClient client, OrderGateway gateway) {}

    public static Pair create(AppConfig config, RateLimiter rateLimiter) {
        ExchangeMode mode = config.mode();
        if (mode.isFutures()) {
            BinanceFuturesClient fc = new BinanceFuturesClient(config, rateLimiter);
            FuturesOrderGateway gw = new FuturesOrderGateway(fc, config);
            return new Pair(fc, gw);
        } else {
            BinanceSpotClient sc = new BinanceSpotClient(config, rateLimiter);
            SpotOrderGateway gw = new SpotOrderGateway(sc);
            return new Pair(sc, gw);
        }
    }
}
