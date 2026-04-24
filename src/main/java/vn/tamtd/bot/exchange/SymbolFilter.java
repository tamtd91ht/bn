package vn.tamtd.bot.exchange;

import java.math.BigDecimal;

/**
 * Các filter của 1 symbol Binance Spot (LOT_SIZE / PRICE_FILTER / NOTIONAL).
 * Parse từ endpoint {@code GET /api/v3/exchangeInfo}.
 */
public record SymbolFilter(
        String symbol,
        String baseAsset,
        String quoteAsset,
        int baseAssetPrecision,
        int quoteAssetPrecision,
        // LOT_SIZE
        BigDecimal lotMinQty,
        BigDecimal lotMaxQty,
        BigDecimal lotStepSize,
        // PRICE_FILTER
        BigDecimal priceMin,
        BigDecimal priceMax,
        BigDecimal priceTickSize,
        // NOTIONAL (mới) / MIN_NOTIONAL (cũ)
        BigDecimal minNotional
) {}
