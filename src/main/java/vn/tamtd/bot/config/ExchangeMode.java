package vn.tamtd.bot.config;

/**
 * Chế độ giao dịch của bot. Đọc từ {@code exchange.mode} trong app.yml.
 */
public enum ExchangeMode {
    SPOT,
    USDM_FUTURES;

    public boolean isFutures() {
        return this == USDM_FUTURES;
    }

    public static ExchangeMode parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("exchange.mode không được rỗng");
        String n = raw.trim().toUpperCase().replace('-', '_');
        return switch (n) {
            case "SPOT" -> SPOT;
            case "USDM_FUTURES", "USDM", "USDT_FUTURES", "FUTURES" -> USDM_FUTURES;
            default -> throw new IllegalArgumentException("exchange.mode không hợp lệ: " + raw
                    + " (cho phép: SPOT | USDM_FUTURES)");
        };
    }
}
