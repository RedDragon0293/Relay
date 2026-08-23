package cn.reddragon.relay.tcp

import cn.reddragon.relay.model.NotificationData

/** Serial, bounded dispatcher for notification messages. */
internal class NotificationDispatcher(
    sender: NotificationSender = TcpClient,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    sleeper: (Long) -> Unit = { Thread.sleep(it) },
    logger: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) {
    private val dispatcher = TcpDispatcher<NotificationData>(
        sender = { notification, config -> sender.send(notification, config) },
        queueCapacity = queueCapacity,
        maxAttempts = maxAttempts,
        retryDelayMs = retryDelayMs,
        sleeper = sleeper,
        logger = logger,
    )

    fun enqueue(
        notification: NotificationData,
        config: ServerConfig,
        sourcePackage: String,
        onComplete: () -> Unit,
    ) {
        dispatcher.enqueue(notification, config, sourcePackage, onComplete)
    }

    internal fun shutdownNow() = dispatcher.shutdownNow()

    companion object {
        internal const val DEFAULT_QUEUE_CAPACITY = TcpDispatcher.DEFAULT_QUEUE_CAPACITY
        internal const val DEFAULT_MAX_ATTEMPTS = TcpDispatcher.DEFAULT_MAX_ATTEMPTS
        internal const val DEFAULT_RETRY_DELAY_MS = TcpDispatcher.DEFAULT_RETRY_DELAY_MS
    }
}
