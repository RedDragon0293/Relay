package cn.reddragon.relay.tcp

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serial, bounded dispatcher shared by notification and SMS transports.
 *
 * The queue contains only in-process work. When it is full, the oldest waiting
 * task is completed as dropped before the newest task is admitted.
 */
internal class TcpDispatcher<T>(
    private val sender: (T, ServerConfig) -> SendResult,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val logger: (message: String, error: Throwable?) -> Unit = { _, _ -> },
) {
    private val executor: ThreadPoolExecutor

    init {
        require(queueCapacity > 0)
        require(maxAttempts > 0)
        require(retryDelayMs >= 0)

        val queue = ArrayBlockingQueue<Runnable>(queueCapacity)
        executor = ThreadPoolExecutor(
            1,
            1,
            THREAD_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            queue,
            ThreadFactory { runnable ->
                Thread(runnable, "RelayTcpDispatcher").apply { isDaemon = true }
            },
        ) { rejected, pool ->
            val dropped = pool.queue.poll()
            (dropped as? Droppable)?.drop("Dropped oldest queued message")

            if (!pool.queue.offer(rejected)) {
                (rejected as? Droppable)?.drop("Dropped incoming message")
            }
        }
        executor.allowCoreThreadTimeOut(true)
    }

    fun enqueue(
        payload: T,
        config: ServerConfig,
        source: String,
        onComplete: () -> Unit,
    ) {
        executor.execute(SendTask(payload, config, source, onComplete))
    }

    fun shutdownNow() {
        executor.shutdownNow()
            .filterIsInstance<Droppable>()
            .forEach { it.drop("Dispatcher shut down") }
    }

    private interface Droppable {
        fun drop(reason: String)
    }

    private inner class SendTask(
        private val payload: T,
        private val config: ServerConfig,
        private val source: String,
        private val onComplete: () -> Unit,
    ) : Runnable, Droppable {
        private val completed = AtomicBoolean(false)

        override fun run() {
            var lastError: Throwable? = null
            try {
                for (attempt in 1..maxAttempts) {
                    when (val result = sender(payload, config)) {
                        SendResult.Success -> {
                            logger(
                                "TCP sent: source=$source, endpoint=${config.host}:${config.port}",
                                null,
                            )
                            return
                        }

                        is SendResult.Failure -> lastError = result.cause
                    }

                    if (attempt < maxAttempts && retryDelayMs > 0) sleeper(retryDelayMs)
                }

                logger(
                    "TCP send failed after $maxAttempts attempts: " +
                        "source=$source, endpoint=${config.host}:${config.port}, " +
                        "error=${lastError?.javaClass?.simpleName.orEmpty()}",
                    lastError,
                )
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                logger("TCP dispatcher interrupted: source=$source", error)
            } catch (error: Throwable) {
                logger(
                    "TCP dispatcher failed: source=$source, " +
                        "error=${error.javaClass.simpleName}",
                    error,
                )
            } finally {
                complete()
            }
        }

        override fun drop(reason: String) {
            logger("$reason: source=$source", null)
            complete()
        }

        private fun complete() {
            if (completed.compareAndSet(false, true)) runCatching(onComplete)
        }
    }

    companion object {
        internal const val DEFAULT_QUEUE_CAPACITY = 2
        internal const val DEFAULT_MAX_ATTEMPTS = 2
        internal const val DEFAULT_RETRY_DELAY_MS = 250L
        private const val THREAD_KEEP_ALIVE_SECONDS = 30L
    }
}
