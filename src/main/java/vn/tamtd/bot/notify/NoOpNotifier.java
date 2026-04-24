package vn.tamtd.bot.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Notifier không gửi đi đâu, dùng khi telegram.enabled=false.
 * Vẫn log event ra console ở mức INFO để user monitor qua app.log.
 */
public final class NoOpNotifier implements Notifier {
    private static final Logger log = LoggerFactory.getLogger(NoOpNotifier.class);

    @Override
    public void send(NotifyEvent event, String message) {
        log.info("[NOTIFY:{}] {} (telegram disabled, chỉ log console)", event, message);
    }
}
