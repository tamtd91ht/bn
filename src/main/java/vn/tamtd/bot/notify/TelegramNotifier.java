package vn.tamtd.bot.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;

import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Gửi alert qua Telegram Bot API. Fail-soft: lỗi mạng/HTTP log nhưng không throw.
 * Đọc {@code telegram.alertEvents} qua {@link ConfigRegistry} → tự pick up sau hot-reload.
 */
public final class TelegramNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConfigRegistry configRegistry;
    private final OkHttpClient http;

    public TelegramNotifier(ConfigRegistry configRegistry) {
        this.configRegistry = configRegistry;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
        AppConfig config = configRegistry.current();
        int tokenLen = config.secrets().telegramBotToken() == null ? 0
                : config.secrets().telegramBotToken().length();
        log.info("TelegramNotifier init: chatId={}, tokenLen={}, alertEvents={}",
                config.secrets().telegramChatId(), tokenLen,
                config.telegram().alertEvents());
    }

    @Override
    public void send(NotifyEvent event, String message) {
        log.info("[NOTIFY:{}] {}", event, message);
        AppConfig config = configRegistry.current();
        Set<NotifyEvent> enabled = parseEnabledEvents(config);
        if (!enabled.contains(event)) return;

        String botToken = config.secrets().telegramBotToken();
        String chatId = config.secrets().telegramChatId();
        if (chatId == null || chatId.isBlank() || botToken == null || botToken.isBlank()) {
            log.warn("[TG:SKIP] event={} thiếu chatId/botToken", event);
            return;
        }
        try {
            String body = prefixFor(event) + " " + message;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", body);
            payload.put("disable_web_page_preview", true);
            String json = MAPPER.writeValueAsString(payload);
            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + botToken + "/sendMessage")
                    .post(RequestBody.create(json, JSON))
                    .build();
            long t0 = System.currentTimeMillis();
            try (Response resp = http.newCall(req).execute()) {
                long dt = System.currentTimeMillis() - t0;
                if (!resp.isSuccessful()) {
                    String respBody = resp.body() == null ? "" : resp.body().string();
                    log.warn("[TG:FAIL] event={} HTTP={} body={}", event, resp.code(), respBody);
                } else {
                    log.debug("[TG:OK] event={} latency={}ms", event, dt);
                }
            }
        } catch (Exception e) {
            log.warn("[TG:ERROR] event={}: {}", event, e.getMessage());
        }
    }

    private Set<NotifyEvent> parseEnabledEvents(AppConfig config) {
        Set<NotifyEvent> set = EnumSet.noneOf(NotifyEvent.class);
        for (String e : config.telegram().alertEvents()) {
            try { set.add(NotifyEvent.valueOf(e)); } catch (IllegalArgumentException ignored) {}
        }
        return set;
    }

    private static String prefixFor(NotifyEvent e) {
        return switch (e) {
            case ENTRY -> "[BUY]";
            case PARTIAL_TP -> "[TP-50%]";
            case FULL_TP -> "[TP-FULL]";
            case STOP_LOSS -> "[SL]";
            case KILL_SWITCH -> "[KILL-SWITCH]";
            case REBALANCE -> "[REBAL]";
            case OPPORTUNITY_MISSED -> "[MISS]";
            case LIQUIDATION_WARN -> "[LIQ-WARN]";
            case FUNDING_HIGH -> "[FUNDING]";
            case CONFIG_RELOADED -> "[CONFIG]";
            case ERROR -> "[ERROR]";
            case DAILY_REPORT -> "[DAILY]";
            case WEEKLY_REPORT -> "[WEEKLY]";
            case MONTHLY_REPORT -> "[MONTHLY]";
            case APP_START -> "[START]";
            case APP_STOP -> "[STOP]";
        };
    }
}
