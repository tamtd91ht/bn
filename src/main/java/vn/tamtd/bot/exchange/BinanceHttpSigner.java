package vn.tamtd.bot.exchange;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Helper ký query string theo cách Binance yêu cầu cho SIGNED endpoint:
 * <pre>
 *   query = timestamp=..&recvWindow=..&...
 *   signature = HMAC_SHA256(query, apiSecret)
 *   url = {baseUrl}/{path}?{query}&signature={signature}
 * </pre>
 */
final class BinanceHttpSigner {

    private BinanceHttpSigner() {}

    /** URL-encode value theo chuẩn application/x-www-form-urlencoded. */
    static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String buildQuery(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        // Giữ thứ tự insertion (LinkedHashMap / TreeMap tuỳ caller) - Binance không yêu cầu sort
        for (Map.Entry<String, Object> e : params.entrySet()) {
            if (e.getValue() == null) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(encode(e.getKey())).append('=').append(encode(String.valueOf(e.getValue())));
        }
        return sb.toString();
    }

    static String hmacSha256Hex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC SHA-256 lỗi", e);
        }
    }

    /** Convenience: tạo TreeMap giữ thứ tự alphabet (không bắt buộc nhưng consistent). */
    static TreeMap<String, Object> sortedParams() {
        return new TreeMap<>();
    }
}
