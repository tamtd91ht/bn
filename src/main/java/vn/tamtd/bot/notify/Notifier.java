package vn.tamtd.bot.notify;

/**
 * Interface gửi notification. Có 2 implementation:
 * <ul>
 *   <li>{@link TelegramNotifier} - gửi qua Telegram Bot API</li>
 *   <li>{@link NoOpNotifier} - không làm gì (khi telegram.enabled=false)</li>
 * </ul>
 */
public interface Notifier {
    void send(NotifyEvent event, String message);
}
