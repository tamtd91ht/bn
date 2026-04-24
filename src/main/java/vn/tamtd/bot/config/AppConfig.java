package vn.tamtd.bot.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Root config - 1 snapshot immutable, được load từ {@code app.yml} (cạnh jar)
 * + merge với {@code symbols.yml} (optional per-symbol override)
 * + env var (secrets + placeholder substitution).
 *
 * <p>Cấu trúc YAML phẳng, tối giản - mỗi section là 1 record để Jackson tự map.
 * Xem file {@code app.yml} bundled trong jar để có ví dụ đầy đủ có comment.
 *
 * <p><b>Hot-reload</b>: snapshot này là IMMUTABLE. Khi file YAML đổi,
 * {@link ConfigRegistry} sẽ load và swap snapshot mới - code đang chạy tick cũ
 * dùng snapshot cũ xuyên suốt tick, an toàn.
 */
public record AppConfig(
        Dynamic dynamic,
        Exchange exchange,
        Signals signals,
        Exit exit,
        Capital capital,
        Risk risk,
        Watchlist watchlist,
        Scanner scanner,
        Timeframes timeframes,
        Scheduling scheduling,
        Storage storage,
        Logging logging,
        Telegram telegram,
        /** Per-symbol override. Key = symbol (VD "DOGEUSDT"). */
        Map<String, SymbolOverride> symbols,
        /** Secrets không có trong YAML - đọc từ env var + secrets.properties. */
        Secrets secrets
) {

    public AppConfig {
        // Defensive: chuẩn hoá các null collection về empty để tránh NPE downstream
        if (symbols == null) symbols = Map.of();
    }

    /** Shortcut tiện dùng ở strategy/order code. */
    @JsonIgnore
    public ExchangeMode mode() {
        return ExchangeMode.parse(exchange.mode());
    }

    /** Leverage effective cho symbol (override > global; mặc định 1 nếu SPOT). */
    public int leverageFor(String symbol) {
        SymbolOverride o = symbols.get(symbol);
        if (o != null && o.leverage() != null) return o.leverage();
        return exchange.leverage();
    }

    /** {@link Exit} effective cho 1 symbol: merge {@code symbols[sym].exit} lên {@code exit}. */
    public Exit exitFor(String symbol) {
        SymbolOverride o = symbols.get(symbol);
        if (o == null || o.exit() == null) return exit;
        Exit e = o.exit();
        return new Exit(
                e.takeProfitPct() != null ? e.takeProfitPct() : exit.takeProfitPct(),
                e.partialTpRatio() != null ? e.partialTpRatio() : exit.partialTpRatio(),
                e.trailingTpStepPct() != null ? e.trailingTpStepPct() : exit.trailingTpStepPct(),
                e.stopLossPct() != null ? e.stopLossPct() : exit.stopLossPct(),
                e.maxDropChecks() != null ? e.maxDropChecks() : exit.maxDropChecks(),
                e.recoveryResetPct() != null ? e.recoveryResetPct() : exit.recoveryResetPct(),
                e.breakevenAfterPartialTp() != null ? e.breakevenAfterPartialTp() : exit.breakevenAfterPartialTp(),
                e.cooldownAfterLossHours() != null ? e.cooldownAfterLossHours() : exit.cooldownAfterLossHours()
        );
    }

    public boolean isSymbolEnabled(String symbol) {
        SymbolOverride o = symbols.get(symbol);
        return o == null || o.enabled() == null || o.enabled();
    }

    /** Validate config - gọi sau khi load xong. Throw {@link IllegalStateException} nếu sai. */
    public void validate() {
        if (exchange == null) fail("exchange null");
        // parse mode sẽ throw nếu sai
        ExchangeMode m = ExchangeMode.parse(exchange.mode());
        if (exchange.leverage() < 1) fail("exchange.leverage >= 1");
        if (m.isFutures()) {
            if (exchange.leverage() > 125) fail("exchange.leverage <= 125 cho USDⓈ-M");
            if (exchange.marginType() == null
                    || !(exchange.marginType().equalsIgnoreCase("ISOLATED")
                    || exchange.marginType().equalsIgnoreCase("CROSSED"))) {
                fail("exchange.marginType phải là ISOLATED hoặc CROSSED khi futures");
            }
            if (exchange.positionMode() == null
                    || !(exchange.positionMode().equalsIgnoreCase("ONE_WAY")
                    || exchange.positionMode().equalsIgnoreCase("HEDGE"))) {
                fail("exchange.positionMode phải là ONE_WAY hoặc HEDGE khi futures");
            }
        }
        if (watchlist == null || watchlist.symbols() == null)
            fail("watchlist section không được null");
        boolean watchlistEmpty = watchlist.symbols().isEmpty();
        boolean scannerDisabled = scanner == null || !scanner.enabled();
        if (watchlistEmpty && scannerDisabled)
            fail("watchlist.symbols rỗng và scanner.enabled=false → bot không có nguồn tín hiệu nào");
        if (capital == null || capital.reservePct() < 0 || capital.reservePct() >= 1)
            fail("capital.reservePct phải trong [0, 1)");
        if (exit == null) fail("exit section không được null");
        if (exit.takeProfitPct() <= 0) fail("exit.takeProfitPct > 0");
        if (exit.stopLossPct() <= 0) fail("exit.stopLossPct > 0");
        if (exit.partialTpRatio() < 0 || exit.partialTpRatio() > 1)
            fail("exit.partialTpRatio ∈ [0,1]");
        if (scheduling == null || scheduling.tickMinutes() <= 0)
            fail("scheduling.tickMinutes > 0");
        if (signals == null) fail("signals section không được null");
        if (signals.emaShort() >= signals.emaLong())
            fail("signals.emaShort < signals.emaLong");
    }

    /** Validate thêm các trường cần khi chạy live (API key, secrets). */
    public void validateSecretsForTrading() {
        if (secrets == null || isBlank(secrets.binanceApiKey()))
            fail("Env BINANCE_API_KEY chưa được set");
        if (isBlank(secrets.binanceApiSecret()))
            fail("Env BINANCE_API_SECRET chưa được set");
        if (telegram != null && telegram.enabled()) {
            if (isBlank(secrets.telegramBotToken()))
                fail("telegram.enabled=true nhưng TELEGRAM_BOT_TOKEN chưa set");
            if (isBlank(secrets.telegramChatId()))
                fail("telegram.enabled=true nhưng TELEGRAM_CHAT_ID chưa set");
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static void fail(String msg) { throw new IllegalStateException("Config không hợp lệ: " + msg); }

    // ================ Nested records ================

    public record Dynamic(
            boolean hotReload,
            long reloadDebounceMs,
            boolean telegramControl
    ) {}

    public record Exchange(
            /** "SPOT" | "USDM_FUTURES" */
            String mode,
            boolean useTestnet,
            int recvWindow,
            /** Default 1 (spot). Futures: 1-125. */
            int leverage,
            /** "ISOLATED" | "CROSSED" - chỉ dùng khi futures. */
            String marginType,
            /** "ONE_WAY" | "HEDGE" - chỉ dùng khi futures. */
            String positionMode
    ) {}

    public record Signals(
            int emaShort,
            int emaLong,
            int rsiPeriod,
            int rsiEntryMin,
            int rsiEntryMax,
            int rsiOversoldEnter,
            int rsiOversoldExit,
            double sidewayEmaGapPct,
            int slopeConfirmBars
    ) {}

    /** Tham số exit - có thể override per-symbol qua {@link SymbolOverride#exit()}. */
    public record Exit(
            Double takeProfitPct,
            Double partialTpRatio,
            Double trailingTpStepPct,
            Double stopLossPct,
            Integer maxDropChecks,
            Double recoveryResetPct,
            Boolean breakevenAfterPartialTp,
            Integer cooldownAfterLossHours
    ) {
        // Helpers unbox với null-check cho các chỗ code cần primitive
        public double takeProfitPctV()       { return takeProfitPct; }
        public double partialTpRatioV()      { return partialTpRatio; }
        public double trailingTpStepPctV()   { return trailingTpStepPct; }
        public double stopLossPctV()         { return stopLossPct; }
        public int    maxDropChecksV()       { return maxDropChecks; }
        public double recoveryResetPctV()    { return recoveryResetPct; }
        public boolean breakevenAfterPartialTpV() { return breakevenAfterPartialTp; }
        public int    cooldownAfterLossHoursV() { return cooldownAfterLossHours; }
    }

    public record Capital(
            double reservePct,
            int v0SnapshotEveryHours,
            double minTradeSizeUsdt,
            double reserveAllocPerOpportunityUsdt,
            /** Cho phép mua lại chính coin đang hold sau khi đã partial TP (pyramid add).
             *  Default false để giữ hành vi v1: mỗi coin = 1 entry. */
            Boolean allowTopUpAfterPartial,
            /** Chỉ top-up nếu position hiện còn < X% originalQty (VD 0.5 = đã bán hơn 1 nửa).
             *  Tránh pyramid liên tục khi partial mới xảy ra 1 chút. */
            Double topUpMinShrinkRatio,
            /** Tổng số lần top-up tối đa cho 1 position (tránh "all-in" cascade). */
            Integer topUpMaxCount
    ) {
        public Capital {
            if (allowTopUpAfterPartial == null) allowTopUpAfterPartial = false;
            if (topUpMinShrinkRatio == null) topUpMinShrinkRatio = 0.5;
            if (topUpMaxCount == null) topUpMaxCount = 2;
        }
        public boolean allowTopUpAfterPartialV() { return allowTopUpAfterPartial; }
        public double topUpMinShrinkRatioV() { return topUpMinShrinkRatio; }
        public int topUpMaxCountV() { return topUpMaxCount; }
    }

    public record Risk(
            double dailyDrawdownPct,
            int pauseHours,
            int maxConcurrentPositions,
            double maxPnlPctEligibleForRebalance,
            /** Futures only: khoảng cách tối thiểu (% từ entry) đến liquidationPrice. */
            double liquidationBufferPct,
            /** Futures only: tổng notional mở tối đa (% equity). */
            double maxExposurePctOfEquity,
            boolean fundingGuardEnabled,
            double fundingMaxAcceptablePctPer8h
    ) {}

    public record Watchlist(
            boolean buyOnStart,
            List<String> symbols
    ) {
        public Watchlist {
            if (symbols == null) symbols = List.of();
            else symbols = Collections.unmodifiableList(symbols);
        }
    }

    public record Scanner(
            boolean enabled,
            int topN,
            double minQuoteVolume24hUsdt,
            List<String> blacklist
    ) {
        public Scanner {
            if (blacklist == null) blacklist = List.of();
            else blacklist = Collections.unmodifiableList(blacklist);
        }
    }

    public record Timeframes(
            String primary,
            String confirm
    ) {}

    public record Scheduling(
            int tickMinutes,
            int cleanupHourVN,
            int dailyReportHourVN
    ) {}

    public record Storage(
            String dataDir,
            int keepDays
    ) {}

    public record Logging(
            boolean console,
            boolean file,
            String level
    ) {}

    public record Telegram(
            boolean enabled,
            List<String> alertEvents
    ) {
        public Telegram {
            if (alertEvents == null) alertEvents = List.of();
            else alertEvents = Collections.unmodifiableList(alertEvents);
        }
    }

    /** Override config cho 1 symbol cụ thể. Tất cả field tuỳ chọn (null = không override). */
    public record SymbolOverride(
            Boolean enabled,
            Integer leverage,
            Exit exit
    ) {}

    /** Secrets đọc từ env var hoặc /secrets.properties bundled. */
    public record Secrets(
            @JsonProperty("binanceApiKey") String binanceApiKey,
            @JsonProperty("binanceApiSecret") String binanceApiSecret,
            @JsonProperty("telegramBotToken") String telegramBotToken,
            @JsonProperty("telegramChatId") String telegramChatId
    ) {}
}
