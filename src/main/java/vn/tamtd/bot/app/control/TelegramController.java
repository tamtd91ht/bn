package vn.tamtd.bot.app.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.tamtd.bot.config.AppConfig;
import vn.tamtd.bot.config.ConfigRegistry;
import vn.tamtd.bot.exchange.ExchangeClient;
import vn.tamtd.bot.executor.OrderExecutor;
import vn.tamtd.bot.storage.BotState;
import vn.tamtd.bot.storage.Position;
import vn.tamtd.bot.storage.StateStore;
import vn.tamtd.bot.strategy.Decision;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Long-polling Telegram để nhận lệnh control. Read-only + emergency brake:
 *
 * <pre>
 *   /status      – snapshot config + positions + equity
 *   /positions   – chi tiết từng vị thế đang mở
 *   /balance     – số dư
 *   /pause       – dừng mở entry mới (không đóng vị thế)
 *   /resume      – mở lại entry
 *   /reload      – force reload app.yml / symbols.yml ngay
 *   /closeAll YES – đóng tất cả vị thế. Phải có tham số "YES" để xác nhận.
 * </pre>
 *
 * <p>Không có {@code /set} để đổi số runtime — sửa YAML thay vì vậy, rõ ràng + auditable.
 *
 * <p>Chỉ phản hồi message từ chat_id match {@code secrets.telegramChatId} để chặn user lạ.
 */
public final class TelegramController {

    private static final Logger log = LoggerFactory.getLogger(TelegramController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ConfigRegistry configRegistry;
    private final BotState state;
    private final StateStore stateStore;
    private final ExchangeClient exchangeClient;
    private final OrderExecutor orderExecutor;

    private final OkHttpClient http;
    private ScheduledExecutorService scheduler;
    private volatile long lastUpdateId = 0;
    private volatile boolean running;

    public TelegramController(ConfigRegistry configRegistry,
                              BotState state,
                              StateStore stateStore,
                              ExchangeClient exchangeClient,
                              OrderExecutor orderExecutor) {
        this.configRegistry = configRegistry;
        this.state = state;
        this.stateStore = stateStore;
        this.exchangeClient = exchangeClient;
        this.orderExecutor = orderExecutor;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(35))
                .build();
    }

    public void start() {
        AppConfig config = configRegistry.current();
        if (!config.dynamic().telegramControl()) {
            log.info("[TG-CTRL] telegramControl=false → không start");
            return;
        }
        if (!config.telegram().enabled()) {
            log.info("[TG-CTRL] telegram.enabled=false → không start");
            return;
        }
        String botToken = config.secrets().telegramBotToken();
        if (botToken == null || botToken.isBlank()) {
            log.warn("[TG-CTRL] telegramBotToken rỗng → không start");
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telegram-control");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::pollOnce, 2, 2, TimeUnit.SECONDS);
        log.info("[TG-CTRL] Started long-polling (commands: /status /positions /balance /pause /resume /reload /closeAll)");
    }

    public void stop() {
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void pollOnce() {
        if (!running) return;
        AppConfig config = configRegistry.current();
        String botToken = config.secrets().telegramBotToken();
        String chatIdAllowed = config.secrets().telegramChatId();
        try {
            HttpUrl url = HttpUrl.parse("https://api.telegram.org/bot" + botToken + "/getUpdates")
                    .newBuilder()
                    .addQueryParameter("timeout", "25")
                    .addQueryParameter("offset", String.valueOf(lastUpdateId + 1))
                    .build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) return;
                JsonNode root = MAPPER.readTree(resp.body().string());
                if (!root.path("ok").asBoolean()) return;
                for (JsonNode upd : root.get("result")) {
                    lastUpdateId = upd.get("update_id").asLong();
                    JsonNode msg = upd.path("message");
                    if (msg.isMissingNode()) continue;
                    String chatId = msg.path("chat").path("id").asText();
                    String text = msg.path("text").asText("");
                    if (chatIdAllowed != null && !chatIdAllowed.equals(chatId)) {
                        log.warn("[TG-CTRL] Message từ chat_id lạ {}: {}", chatId, text);
                        continue;
                    }
                    handleCommand(text, chatId, botToken);
                }
            }
        } catch (Exception e) {
            log.warn("[TG-CTRL] Poll lỗi: {}", e.getMessage());
        }
    }

    private void handleCommand(String text, String chatId, String botToken) {
        if (text == null || text.isBlank()) return;
        String raw = text.trim();
        String cmd = raw.split("\\s+")[0].toLowerCase();
        if (cmd.contains("@")) cmd = cmd.substring(0, cmd.indexOf('@')); // "/status@mybot"
        log.info("[TG-CTRL] Cmd: {}", raw);
        try {
            String reply = switch (cmd) {
                case "/status" -> renderStatus();
                case "/positions" -> renderPositions();
                case "/balance" -> renderBalance();
                case "/pause" -> { state.paused = true; stateStore.save(state);
                        yield "⏸ Bot đã PAUSE - không mở entry mới. Vị thế cũ vẫn được manage."; }
                case "/resume" -> { state.paused = false; stateStore.save(state);
                        yield "▶ Bot đã RESUME"; }
                case "/reload" -> configRegistry.reload()
                        ? "🔄 Config reloaded.\n" + summariseConfig()
                        : "⚠ Reload không có thay đổi hoặc thất bại (xem log)";
                case "/closeall" -> handleCloseAll(raw);
                case "/help", "/start" -> helpText();
                default -> "Lệnh không nhận: " + cmd + "\n" + helpText();
            };
            sendReply(botToken, chatId, reply);
        } catch (Exception e) {
            log.error("[TG-CTRL] Handle cmd lỗi", e);
            sendReply(botToken, chatId, "❌ Lỗi: " + e.getMessage());
        }
    }

    private String handleCloseAll(String raw) {
        if (!raw.toUpperCase().contains("YES")) {
            return "⚠ /closeAll nguy hiểm - gõ `/closeAll YES` để xác nhận đóng tất cả vị thế.";
        }
        int n = state.positions.size();
        if (n == 0) return "Không có vị thế nào đang mở.";
        long tickTs = System.currentTimeMillis();
        for (Position p : new java.util.ArrayList<>(state.positions.values())) {
            Decision.KillSwitchSellAll d = new Decision.KillSwitchSellAll(
                    p.symbol, p.qty, "Manual close via Telegram /closeAll");
            orderExecutor.execute(d, state, tickTs);
        }
        return "🛑 Đã gửi lệnh close cho " + n + " vị thế.";
    }

    private String renderStatus() {
        AppConfig config = configRegistry.current();
        StringBuilder sb = new StringBuilder();
        sb.append("📊 STATUS\n");
        sb.append(summariseConfig()).append("\n\n");
        sb.append("paused=").append(state.paused)
                .append(" killedUntil=").append(state.killedUntil == null ? "-" : state.killedUntil)
                .append("\n");
        sb.append(String.format("v0=%.2f reserveFund=%.2f%n", state.v0, state.reserveFund));
        sb.append("positions: ").append(state.positions.size()).append("\n");
        for (Position p : state.positions.values()) {
            sb.append(String.format("  • %s qty=%s entry=%s lev=%s src=%s%n",
                    p.symbol, p.qty.toPlainString(), p.entryPrice.toPlainString(),
                    p.leverage == null ? "1" : p.leverage.toString(), p.source));
        }
        return sb.toString();
    }

    private String renderPositions() {
        if (state.positions.isEmpty()) return "Không có vị thế nào.";
        StringBuilder sb = new StringBuilder("📈 POSITIONS\n");
        for (Position p : state.positions.values()) {
            BigDecimal cur;
            try { cur = exchangeClient.latestPrice(p.symbol); } catch (Exception e) { cur = null; }
            double pnl = 0;
            if (cur != null) {
                BigDecimal diff = p.isLong()
                        ? cur.subtract(p.entryPrice)
                        : p.entryPrice.subtract(cur);
                int lev = p.leverage == null ? 1 : p.leverage;
                pnl = diff.divide(p.entryPrice, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100.0 * lev)).doubleValue();
            }
            sb.append(String.format("• %s %s qty=%s entry=%s cur=%s pnl=%+.2f%% lev=%s liq=%s%n",
                    p.symbol, p.isLong() ? "LONG" : "SHORT",
                    p.qty.toPlainString(), p.entryPrice.toPlainString(),
                    cur == null ? "n/a" : cur.toPlainString(), pnl,
                    p.leverage == null ? "1" : p.leverage.toString(),
                    p.liquidationPrice == null ? "-" : p.liquidationPrice.toPlainString()));
        }
        return sb.toString();
    }

    private String renderBalance() {
        try {
            AppConfig config = configRegistry.current();
            String json = exchangeClient.rawAccount();
            JsonNode root = MAPPER.readTree(json);
            if (config.mode().isFutures()) {
                double wallet = root.path("totalWalletBalance").asDouble();
                double unreal = root.path("totalUnrealizedProfit").asDouble();
                double margin = root.path("totalMarginBalance").asDouble();
                double avail = root.path("availableBalance").asDouble();
                return String.format("💰 FUTURES BALANCE%n" +
                                "wallet=%.2f unrealized=%+.2f margin=%.2f available=%.2f",
                        wallet, unreal, margin, avail);
            } else {
                double usdt = 0;
                for (JsonNode bal : root.get("balances")) {
                    if ("USDT".equals(bal.get("asset").asText())) {
                        usdt = bal.get("free").asDouble() + bal.get("locked").asDouble();
                        break;
                    }
                }
                return String.format("💰 SPOT BALANCE%nUSDT=%.2f%nv0=%.2f reserve=%.2f",
                        usdt, state.v0, state.reserveFund);
            }
        } catch (Exception e) {
            return "❌ Lấy balance lỗi: " + e.getMessage();
        }
    }

    private String summariseConfig() {
        AppConfig c = configRegistry.current();
        return String.format("mode=%s lev=%s testnet=%s tick=%dm%n" +
                        "tp=%.1f%% sl=%.1f%% watchlist=%s",
                c.exchange().mode(), c.exchange().leverage(),
                c.exchange().useTestnet(), c.scheduling().tickMinutes(),
                c.exit().takeProfitPctV(), c.exit().stopLossPctV(),
                c.watchlist().symbols());
    }

    private String helpText() {
        return """
                Lệnh hỗ trợ:
                /status       – trạng thái + config tóm tắt
                /positions    – chi tiết vị thế đang mở
                /balance      – số dư
                /pause        – dừng mở entry mới
                /resume       – chạy tiếp
                /reload       – reload app.yml ngay
                /closeAll YES – đóng tất cả vị thế (khẩn cấp)""";
    }

    private void sendReply(String botToken, String chatId, String text) {
        try {
            HttpUrl url = HttpUrl.parse("https://api.telegram.org/bot" + botToken + "/sendMessage")
                    .newBuilder()
                    .addQueryParameter("chat_id", chatId)
                    .addQueryParameter("text", text)
                    .build();
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("[TG-CTRL] sendReply HTTP {} body={}", resp.code(),
                            resp.body() == null ? "" : resp.body().string());
                }
            }
        } catch (Exception e) {
            log.warn("[TG-CTRL] sendReply lỗi: {}", e.getMessage());
        }
    }
}
