package vn.tamtd.bot.notify;

/**
 * Các loại event notifier có thể gửi. Phải khớp với string trong {@code telegram.alertEvents} YAML.
 */
public enum NotifyEvent {
    ENTRY,
    PARTIAL_TP,
    FULL_TP,
    STOP_LOSS,
    KILL_SWITCH,
    REBALANCE,
    OPPORTUNITY_MISSED,
    /** Futures only: giá gần liquidation. */
    LIQUIDATION_WARN,
    /** Futures only: funding rate quá cao, skip mở long mới. */
    FUNDING_HIGH,
    /** Config được hot-reload (dynamic.hotReload=true). */
    CONFIG_RELOADED,
    ERROR,
    DAILY_REPORT,
    WEEKLY_REPORT,
    MONTHLY_REPORT,
    APP_START,
    APP_STOP
}
