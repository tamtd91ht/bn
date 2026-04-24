package vn.tamtd.bot.exchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * {@link OrderGateway} cho Binance Spot. Mua: quote-based ({@code quoteOrderQty}).
 * Bán: qty-based. Không có SHORT.
 */
public final class SpotOrderGateway implements OrderGateway {

    private static final Logger log = LoggerFactory.getLogger(SpotOrderGateway.class);

    private final BinanceSpotClient client;

    public SpotOrderGateway(BinanceSpotClient client) {
        this.client = client;
    }

    @Override
    public String openLong(OpenRequest r) {
        if (r.quoteAmountUsdt() != null) {
            return client.marketBuyByQuote(r.symbol(), r.quoteAmountUsdt(), r.clientOrderId());
        }
        throw new IllegalArgumentException("Spot openLong cần quoteAmountUsdt, không dùng qty");
    }

    @Override
    public String openShort(OpenRequest r) {
        throw new UnsupportedOperationException("Spot không hỗ trợ SHORT");
    }

    @Override
    public String close(CloseRequest r) {
        // Spot luôn sell coin -> USDT
        return client.marketSellByQty(r.symbol(), r.qty(), r.clientOrderId());
    }

    @Override
    public void setLeverage(String symbol, int leverage) {
        if (leverage != 1) {
            log.debug("[SPOT] setLeverage({}) bị ignore cho spot", leverage);
        }
    }

    @Override
    public void setMarginType(String symbol, String marginType) {
        // no-op cho spot
    }
}
