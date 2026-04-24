package vn.tamtd.bot.scanner;

/**
 * Kết quả quét của {@link UniverseScanner} cho 1 symbol.
 */
public record ScanResult(
        String symbol,
        double quoteVolume24h,
        double pctChange24h,
        Signal signal,
        double score
) {
    public enum Signal {
        NONE,
        UPTREND_EMERGING,
        BOTTOM_REVERSAL,
        STRONG_WAVE
    }
}
