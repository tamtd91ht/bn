package vn.tamtd.bot.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BarSeries;
import vn.tamtd.bot.exchange.ExchangeClient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Fetch historical klines qua {@link ExchangeClient#rawKlines} rồi build ta4j BarSeries.
 * Hoạt động cho cả Spot (/api/v3/klines) và Futures (/fapi/v1/klines) - format response giống nhau.
 */
public final class KlineFetcher {

    private static final Logger log = LoggerFactory.getLogger(KlineFetcher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExchangeClient client;

    public KlineFetcher(ExchangeClient client) {
        this.client = client;
    }

    /**
     * @param interval "1h", "4h", "15m", "1d", ... (chuẩn Binance)
     */
    public BarSeries fetch(String symbol, String interval, int limit) {
        String json = client.rawKlines(symbol, interval, limit);
        try {
            JsonNode arr = MAPPER.readTree(json);
            Duration period = parseInterval(interval);
            BarSeries series = new BaseBarSeries(symbol + "_" + interval);
            for (JsonNode row : arr) {
                // [ openTime, open, high, low, close, volume, closeTime, quoteVolume, trades, ... ]
                long closeTime = row.get(6).asLong();
                ZonedDateTime endTime = Instant.ofEpochMilli(closeTime + 1).atZone(ZoneOffset.UTC);
                series.addBar(new BaseBar(
                        period,
                        endTime,
                        series.numOf(Double.parseDouble(row.get(1).asText())),
                        series.numOf(Double.parseDouble(row.get(2).asText())),
                        series.numOf(Double.parseDouble(row.get(3).asText())),
                        series.numOf(Double.parseDouble(row.get(4).asText())),
                        series.numOf(Double.parseDouble(row.get(5).asText())),
                        series.numOf(Double.parseDouble(row.get(7).asText())),
                        row.get(8).asLong()
                ));
            }
            return series;
        } catch (Exception e) {
            throw new RuntimeException("Parse klines lỗi: " + symbol + "/" + interval, e);
        }
    }

    private static Duration parseInterval(String s) {
        int val = Integer.parseInt(s.substring(0, s.length() - 1));
        return switch (s.charAt(s.length() - 1)) {
            case 'm' -> Duration.ofMinutes(val);
            case 'h' -> Duration.ofHours(val);
            case 'd' -> Duration.ofDays(val);
            case 'w' -> Duration.ofDays((long) val * 7);
            default -> throw new IllegalArgumentException("Interval không hỗ trợ: " + s);
        };
    }
}
