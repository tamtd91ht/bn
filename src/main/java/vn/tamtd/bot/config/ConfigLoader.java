package vn.tamtd.bot.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Load {@link AppConfig} từ filesystem / classpath + merge symbols.yml + secrets.
 *
 * <h3>Thứ tự load</h3>
 * <ol>
 *   <li>Đọc {@code app.yml} (ưu tiên đường dẫn cạnh jar, fallback classpath bundled).</li>
 *   <li>Đọc {@code symbols.yml} cùng thư mục (nếu có) → merge vào field {@code symbols}.</li>
 *   <li>Load {@link AppConfig.Secrets} từ env var + classpath:/secrets.properties.</li>
 *   <li>Validate, trả AppConfig immutable.</li>
 * </ol>
 */
public final class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    static {
        YAML.findAndRegisterModules();
    }

    public static final String APP_YAML = "app.yml";
    public static final String SYMBOLS_YAML = "symbols.yml";

    private ConfigLoader() {}

    /** Load từ directory cho sẵn. Nếu {@code dir} null → dùng jar dir. */
    public static AppConfig load(Path dir) throws IOException {
        Path baseDir = dir != null ? dir : jarDir();
        log.info("ConfigLoader: baseDir = {}", baseDir.toAbsolutePath());

        AppConfig parsed = readAppYaml(baseDir);
        Map<String, AppConfig.SymbolOverride> syms = readSymbolsYaml(baseDir);
        AppConfig.Secrets secrets = AppSecrets.fromEnvAndBundled();

        AppConfig merged = new AppConfig(
                parsed.dynamic(),
                parsed.exchange(),
                parsed.signals(),
                parsed.exit(),
                parsed.capital(),
                parsed.risk(),
                parsed.watchlist(),
                parsed.scanner(),
                parsed.timeframes(),
                parsed.scheduling(),
                parsed.storage(),
                parsed.logging(),
                parsed.telegram(),
                parsed.recovery(),
                parsed.paperTrade(),
                syms.isEmpty() ? parsed.symbols() : syms,
                secrets
        );
        merged.validate();
        log.info("Config loaded: mode={}, leverage={}, testnet={}, strategy=long_only, "
                        + "watchlist={}, hotReload={}, symbolOverrides={}",
                merged.exchange().mode(), merged.exchange().leverage(),
                merged.exchange().useTestnet(),
                merged.watchlist().symbols(),
                merged.dynamic().hotReload(),
                merged.symbols().keySet());
        return merged;
    }

    /** Load bundled (dùng classpath /app.yml) - cho test / fallback khi chạy từ IDE. */
    public static AppConfig loadBundled() throws IOException {
        AppConfig parsed;
        try (var in = ConfigLoader.class.getResourceAsStream("/" + APP_YAML)) {
            if (in == null) throw new IOException("Không tìm thấy classpath:/app.yml");
            parsed = YAML.readValue(in, AppConfig.class);
        }
        AppConfig.Secrets secrets = AppSecrets.fromEnvAndBundled();
        AppConfig merged = new AppConfig(
                parsed.dynamic(), parsed.exchange(), parsed.signals(), parsed.exit(),
                parsed.capital(), parsed.risk(), parsed.watchlist(), parsed.scanner(),
                parsed.timeframes(), parsed.scheduling(), parsed.storage(),
                parsed.logging(), parsed.telegram(), parsed.recovery(),
                parsed.paperTrade(),
                parsed.symbols() == null ? Map.of() : parsed.symbols(),
                secrets);
        merged.validate();
        return merged;
    }

    private static AppConfig readAppYaml(Path baseDir) throws IOException {
        Path external = baseDir.resolve(APP_YAML);
        if (Files.exists(external)) {
            try (var in = Files.newInputStream(external)) {
                log.info("Đọc app.yml từ: {}", external.toAbsolutePath());
                return YAML.readValue(in, AppConfig.class);
            }
        }
        try (var in = ConfigLoader.class.getResourceAsStream("/" + APP_YAML)) {
            if (in == null) {
                throw new IOException("Không tìm thấy app.yml ở " + external.toAbsolutePath()
                        + " lẫn classpath bundled");
            }
            log.info("Đọc app.yml từ classpath bundled (fallback)");
            return YAML.readValue(in, AppConfig.class);
        }
    }

    private static Map<String, AppConfig.SymbolOverride> readSymbolsYaml(Path baseDir) {
        Path external = baseDir.resolve(SYMBOLS_YAML);
        if (!Files.exists(external)) {
            log.info("Không có symbols.yml ở {} - dùng config global cho mọi coin", baseDir);
            return Map.of();
        }
        try (var in = Files.newInputStream(external)) {
            Map<String, AppConfig.SymbolOverride> m = YAML.readValue(in,
                    new TypeReference<Map<String, AppConfig.SymbolOverride>>() {});
            if (m == null) return Map.of();
            log.info("Đọc symbols.yml: {} override ({})", m.size(), m.keySet());
            return m;
        } catch (IOException e) {
            log.warn("Đọc symbols.yml lỗi - bỏ qua per-symbol override: {}", e.getMessage());
            return Map.of();
        }
    }

    /** Directory chứa file jar (hoặc cwd nếu đang chạy từ IDE). */
    public static Path jarDir() {
        try {
            var codeSource = ConfigLoader.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) return Path.of("").toAbsolutePath();
            var url = codeSource.getLocation();
            var p = Path.of(url.toURI());
            return Files.isDirectory(p) ? p : p.getParent();
        } catch (Exception e) {
            return Path.of("").toAbsolutePath();
        }
    }

    // ======== Secrets ========

    private static final class AppSecrets {
        static AppConfig.Secrets fromEnvAndBundled() {
            Properties bundled = loadBundled();
            return new AppConfig.Secrets(
                    pick(System.getenv("BINANCE_API_KEY"),    bundled.getProperty("binance.api.key")),
                    pick(System.getenv("BINANCE_API_SECRET"), bundled.getProperty("binance.api.secret")),
                    pick(System.getenv("TELEGRAM_BOT_TOKEN"), bundled.getProperty("telegram.bot.token")),
                    pick(System.getenv("TELEGRAM_CHAT_ID"),   bundled.getProperty("telegram.chat.id"))
            );
        }

        static Properties loadBundled() {
            Properties p = new Properties();
            try (var in = ConfigLoader.class.getResourceAsStream("/secrets.properties")) {
                if (in != null) p.load(in);
            } catch (IOException ignored) {}
            return p;
        }

        static String pick(String fromEnv, String fromBundled) {
            if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
            if (fromBundled != null && !fromBundled.isBlank()
                    && !fromBundled.startsWith("your_")
                    && !fromBundled.contains("_replace_")) return fromBundled;
            return null;
        }
    }
}
