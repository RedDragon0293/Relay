package cn.reddragon.relay.tcp

import cn.reddragon.relay.model.NotificationData
import cn.reddragon.relay.model.SmsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class TcpClientTest {
    @Test
    fun encodesExistingNewlineDelimitedJsonFormat() {
        val notification = NotificationData(
            title = "A \"quote\"",
            content = "line1\nline2\\end/path",
        )

        assertEquals(
            "{\"title\":\"A \\\"quote\\\"\",\"content\":\"line1\\nline2\\\\end\\/path\"}\n",
            TcpClient.encodePayload(notification).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun sendsOneJsonLineToServer() {
        val server = ServerSocket(0)
        val received = CompletableFuture<String>()
        val serverThread = Thread {
            server.use {
                it.accept().use { socket ->
                    received.complete(socket.getInputStream().bufferedReader().readLine())
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val result = TcpClient.send(
            NotificationData("title", "content"),
            ServerConfig("127.0.0.1", server.localPort),
        )

        assertSame(SendResult.Success, result)
        assertEquals(
            "{\"title\":\"title\",\"content\":\"content\"}",
            received.get(2, TimeUnit.SECONDS),
        )
        serverThread.join(2000)
    }

    @Test
    fun rejectsInvalidEndpointBeforeOpeningSocket() {
        val result = TcpClient.send(
            NotificationData("title", "content"),
            ServerConfig("", 0),
        )

        assertTrue(result is SendResult.Failure)
    }

    @Test
    fun encodesSmsPayloadWithExpectedWireFields() {
        val sms = SmsData(
            msg = "验证码是 1234\n请勿泄露",
            smsCode = "1234",
        )

        assertEquals(
            "{\"msg\":\"验证码是 1234\\n请勿泄露\",\"sms_code\":\"1234\"}\n",
            TcpClient.encodeSmsPayload(sms).toString(Charsets.UTF_8),
        )
    }
}
