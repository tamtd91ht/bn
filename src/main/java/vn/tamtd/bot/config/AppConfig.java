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
        /** Recovery/rehydrate settings - khôi phục position từ ví khi state file trống. Optional. */
        Recovery recovery,
        /** Per-symbol override. Key = symbol (VD "DOGEUSDT"). */
        Map<String, SymbolOverride> symbols,
        /** Secrets không có trong YAML - đọc từ env var + secrets.properties. */
        Secrets secrets
) {

    public AppConfig {
        // Defensive: chuẩn hoá các null collection về empty để tránh NPE downstream
        if (symbols == null) symbols = Map.of();
        if (recovery == null) recovery = Recovery.defaults();
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
                e.cooldownAfterLossHours() != null ? e.cooldownAfterLossHours() : exit.cooldownAfterLossHours(),
                e.trailingArmPct() != null ? e.trailingArmPct() : exit.trailingArmPct(),
                e.trailingDropPct() != null ? e.trailingDropPct() : exit.trailingDropPct()
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
            int slopeConfirmBars,
            /** STRONG_WAVE 4h: nếu RSI 4h hiện tại > X → reject (tránh catch đỉnh sóng).
             *  null/0 = tắt filter. Khuyến nghị 70 (overbought zone). */
            Integer strongWaveRsiMax
    ) {
        public int strongWaveRsiMaxV() { return strongWaveRsiMax == null ? 0 : strongWaveRsiMax; }
    }

    /** Tham số exit - có thể override per-symbol qua {@link SymbolOverride#exit()}. */
    public record Exit(
            Double takeProfitPct,
            Double partialTpRatio,
            Double trailingTpStepPct,
            Double stopLossPct,
            Integer maxDropChecks,
            Double recoveryResetPct,
            Boolean breakevenAfterPartialTp,
            Integer cooldownAfterLossHours,
            /** Trailing high-water TP: khi pnl% từng đạt ≥ trailingArmPct, enable trailing.
             *  Nếu drop từ đỉnh ≥ trailingDropPct → bán toàn bộ ngay (TP_FULL).
             *  Mục đích: bảo vệ lãi đã đạt được khi chưa hit takeProfitPct.
             *  null/0 = tắt trailing. */
            Double trailingArmPct,
            Double trailingDropPct
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
        public double trailingArmPctV()      { return trailingArmPct == null ? 0.0 : trailingArmPct; }
        public double trailingDropPctV()     { return trailingDropPct == null ? 0.0 : trailingDropPct; }
        public boolean trailingEnabled()     { return trailingArmPctV() > 0 && trailingDropPctV() > 0; }
    }

    public record Capital(
            double reservePct,
            int v0SnapshotEveryHours,
            double minTradeSizeUsdt,
            double reserveAllocPerOpportunityUsdt
    ) {}

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
            double fundingMaxAcceptablePctPer8h,
            /** Số tick liên tiếp drawdown >= ngưỡng mới fire kill-switch. Tránh false trigger từ wick.
             *  1 = fire ngay (như cũ), 3 = cần 3 tick liên tiếp. */
            Integer killSwitchHysteresisTicks
    ) {
        public Risk {
            if (killSwitchHysteresisTicks == null) killSwitchHysteresisTicks = 1;
        }
        public int killSwitchHysteresisTicksV() { return killSwitchHysteresisTicks; }
    }

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
            int dailyReportHourVN,
            /** Fast loop chỉ check positions (TP/SL) mỗi N giây. 0 = tắt fast loop, dùng tick chính. */
            Integer fastTickSeconds,
            /** Bỏ qua check SL trong N giây sau khi entry để tránh fast-tick bán ngay sau entry slip. */
            Integer entryGracePeriodSeconds
    ) {
        public Scheduling {
            if (fastTickSeconds == null) fastTickSeconds = 0;
            if (entryGracePeriodSeconds == null) entryGracePeriodSeconds = 0;
        }
        public int fastTickSecondsV() { return fastTickSeconds; }
        public int entryGracePeriodSecondsV() { return entryGracePeriodSeconds; }
    }

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

    /**
     * Rehydrate khi start: nếu bật + state file trống/thiếu position, bot scan holdings
     * từ ví Binance và tạo Position với {@code entryPrice = currentPrice} - coi như
     * coin vừa mua tại thời điểm start. TP/SL/trailing sẽ manage forward từ mốc đó.
     * PnL lịch sử KHÔNG được khôi phục (bot không biết giá entry thật).
     */
    public record Recovery(
            /** Cho phép rehydrate. Default false - user phải opt-in để tránh bot tự take over
             *  coin mà user đang hodl dài hạn. */
            Boolean rehydrateOnStart,
            /** Coin có USDT value < ngưỡng này → skip (tránh rehydrate dust). */
            Double minAssetValueUsdt,
            /** Chỉ rehydrate nếu state.positions rỗng. False = allow merge (advanced). */
            Boolean onlyWhenStateEmpty
    ) {
        public static Recovery defaults() {
            return new Recovery(false, 1.0, true);
        }
        public Recovery {
            if (rehydrateOnStart == null) rehydrateOnStart = false;
            if (minAssetValueUsdt == null) minAssetValueUsdt = 1.0;
            if (onlyWhenStateEmpty == null) onlyWhenStateEmpty = true;
        }
        public boolean rehydrateOnStartV() { return rehydrateOnStart; }
        public double minAssetValueUsdtV() { return minAssetValueUsdt; }
        public boolean onlyWhenStateEmptyV() { return onlyWhenStateEmpty; }
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
