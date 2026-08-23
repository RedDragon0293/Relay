package cn.reddragon.relay.tcp

import cn.reddragon.relay.model.NotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NotificationDispatcherTest {
    @Test
    fun retriesOnceAndCompletesExactlyOnce() {
        val attempts = AtomicInteger()
        val completions = AtomicInteger()
        val completed = CountDownLatch(1)
        val delays = mutableListOf<Long>()
        val sender = NotificationSender { _, _ ->
            if (attempts.incrementAndGet() == 1) SendResult.Failure(IOException("offline"))
            else SendResult.Success
        }
        val dispatcher = NotificationDispatcher(
            sender = sender,
            maxAttempts = 2,
            retryDelayMs = 250,
            sleeper = { delays += it },
        )

        dispatcher.enqueue(notification("one"), config(), "one.app") {
            completions.incrementAndGet()
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(2, attempts.get())
        assertEquals(listOf(250L), delays)
        assertEquals(1, completions.get())
        dispatcher.shutdownNow()
    }

    @Test
    fun completesAfterAllAttemptsFail() {
        val attempts = AtomicInteger()
        val completions = AtomicInteger()
        val completed = CountDownLatch(1)
        val dispatcher = NotificationDispatcher(
            sender = NotificationSender { _, _ ->
                attempts.incrementAndGet()
                SendResult.Failure(IOException("offline"))
            },
            maxAttempts = 2,
            retryDelayMs = 0,
        )

        dispatcher.enqueue(notification("failed"), config(), "failed.app") {
            completions.incrementAndGet()
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(2, attempts.get())
        assertEquals(1, completions.get())
        dispatcher.shutdownNow()
    }

    @Test
    fun dropsOldestQueuedTaskAndKeepsLatestTwo() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val allCompleted = CountDownLatch(4)
        val sentTitles = Collections.synchronizedList(mutableListOf<String>())
        val sender = NotificationSender { notification, _ ->
            sentTitles += notification.title
            if (notification.title == "one") {
                firstStarted.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
            SendResult.Success
        }
        val dispatcher = NotificationDispatcher(
            sender = sender,
            queueCapacity = 2,
            maxAttempts = 1,
            retryDelayMs = 0,
        )

        dispatcher.enqueue(notification("one"), config(), "one.app", allCompleted::countDown)
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        dispatcher.enqueue(notification("two"), config(), "two.app", allCompleted::countDown)
        dispatcher.enqueue(notification("three"), config(), "three.app", allCompleted::countDown)
        dispatcher.enqueue(notification("four"), config(), "four.app", allCompleted::countDown)
        releaseFirst.countDown()

        assertTrue(allCompleted.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("one", "three", "four"), sentTitles.toList())
        dispatcher.shutdownNow()
    }

    private fun notification(title: String) = NotificationData(title, "content")
    private fun config() = ServerConfig("127.0.0.1", 8080)
}
