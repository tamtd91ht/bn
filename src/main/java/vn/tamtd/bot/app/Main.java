package vn.tamtd.bot.app;

import picocli.CommandLine;

/**
 * Entry point. Subcommand:
 * <pre>
 *   java -jar bot.jar run              # live trading
 *   java -jar bot.jar list-symbols     # top-N USDT pair
 *   java -jar bot.jar close-all        # emergency close
 *   java -jar bot.jar account          # verify API key + balance
 *   java -jar bot.jar scan-once        # 1 vòng phân tích, không đặt lệnh
 * </pre>
 */
@CommandLine.Command(
        name = "bot",
        mixinStandardHelpOptions = true,
        version = "bn-bot 0.2.0",
        description = "Binance Spot + USDⓈ-M Futures auto-trading bot",
        subcommands = {
                RunCommand.class,
                AccountCommand.class,
                ListSymbolsCommand.class,
                CloseAllCommand.class,
                ScanOnceCommand.class
        }
)
public final class Main implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new Main()).execute(args);
        System.exit(exit);
    }
}
