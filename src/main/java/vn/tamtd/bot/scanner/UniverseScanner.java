package vn.tamtd.bot.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.exchange.ExchangeClient;
import vn.tamtd.bot.indicator.SignalDetector;
import vn.tamtd.bot.marketdata.BarSeriesCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Quét top-N USDT pair theo volume 24h, gắn signal.
 * Dùng chung cho Spot và Futures qua {@link ExchangeClient}.
 */
public final class UniverseScanner {

    private static final Logger log = LoggerFactory.getLogger(UniverseScanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConfigRegistry configRegistry;
    private final ExchangeClient client;
    private final BarSeriesCache barSeriesCache;
    private final SignalDetector signalDetector;

    public UniverseScanner(ConfigRegistry configRegistry,
                           ExchangeClient client,
                           BarSeriesCache barSeriesCache,
                           SignalDetector signalDetector) {
        this.configRegistry = configRegistry;
        this.client = client;
        this.barSeriesCache = barSeriesCache;
        this.signalDetector = signalDetector;
    }

    public List<ScanResult> scan() {
        AppConfig config = configRegistry.current();
        Set<String> blacklist = Set.copyOf(config.scanner().blacklist());
        Set<String> watchlist = Set.copyOf(config.watchlist().symbols());
        double minVol = config.scanner().minQuoteVolume24hUsdt();
        int topN = config.scanner().topN();

        log.info("[SCAN] minVol={} topN={} blacklist={} watchlist={}",
                String.format("%,.0f", minVol), topN, blacklist.size(), watchlist);

        List<Candidate> candidates = new ArrayList<>();
        try {
            String tickerJson = client.rawAll24hTicker();
            JsonNode arr = MAPPER.readTree(tickerJson);
            for (JsonNode t : arr) {
                String symbol = t.get("symbol").asText();
                if (!symbol.endsWith("USDT")) continue;
                if (blacklist.contains(symbol)) continue;
                if (watchlist.contains(symbol)) continue;
                double qv = t.get("quoteVolume").asDouble();
                if (qv < minVol) continue;
                double pct = t.get("priceChangePercent").asDouble();
                candidates.add(new Candidate(symbol, qv, pct));
            }
        } catch (Exception e) {
            log.error("[SCAN] Fetch 24h ticker lỗi", e);
            return List.of();
        }

        candidates.sort(Comparator.comparingDouble(Candidate::qv).reversed());
        if (candidates.size() > topN) candidates = candidates.subList(0, topN);

        String tfPrimary = config.timeframes().primary();
        String tfConfirm = config.timeframes().confirm();

        List<ScanResult> results = new ArrayList<>();
        int errCount = 0;
        for (Candidate c : candidates) {
            try {
                BarSeries s1h = barSeriesCache.get(c.symbol, tfPrimary);
                BarSeries s4h = barSeriesCache.get(c.symbol, tfConfirm);

                ScanResult.Signal signal = ScanResult.Signal.NONE;
                if (signalDetector.isStrongWave(s4h)) signal = ScanResult.Signal.STRONG_WAVE;
                else if (signalDetector.isUptrendEmerging(s1h)) signal = ScanResult.Signal.UPTREND_EMERGING;
                else if (signalDetector.isBottomReversal(s1h)) signal = ScanResult.Signal.BOTTOM_REVERSAL;

                double score = signalScore(signal, c.qv);
                results.add(new ScanResult(c.symbol, c.qv, c.pct, signal, score));
            } catch (Exception e) {
                errCount++;
                log.warn("[SCAN] Analyse {} lỗi: {}", c.symbol, e.getMessage());
            }
        }
        results.sort(Comparator
                .<ScanResult>comparingInt(r -> r.signal() == ScanResult.Signal.NONE ? 1 : 0)
                .thenComparingDouble(r -> -r.score()));
        long hits = results.stream().filter(r -> r.signal() != ScanResult.Signal.NONE).count();
        log.info("[SCAN] Done: {} hits / {} scanned, errors={}", hits, results.size(), errCount);
        return results;
    }

    private static double signalScore(ScanResult.Signal sig, double qv) {
        double base = switch (sig) {
            case STRONG_WAVE -> 3.0;
            case UPTREND_EMERGING -> 2.0;
            case BOTTOM_REVERSAL -> 1.5;
            case NONE -> 0.0;
        };
        double volFactor = Math.log10(Math.max(1, qv));
        return base * volFactor;
    }

    private record Candidate(String symbol, double qv, double pct) {}
}
