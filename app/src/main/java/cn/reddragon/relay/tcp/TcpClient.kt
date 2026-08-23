package cn.reddragon.relay.tcp

import cn.reddragon.relay.model.NotificationData
import cn.reddragon.relay.model.SmsData
import java.net.InetSocketAddress
import java.net.Socket

sealed interface SendResult {
    data object Success : SendResult
    data class Failure(val cause: Throwable) : SendResult
}

fun interface NotificationSender {
    fun send(notification: NotificationData, config: ServerConfig): SendResult
}

fun interface SmsSender {
    fun send(sms: SmsData, config: ServerConfig): SendResult
}

/** 每条通知使用独立 TCP 连接，并保持既有的一行 JSON wire 格式。 */
object TcpClient : NotificationSender, SmsSender {
    internal const val CONNECT_TIMEOUT_MS = 1500

    override fun send(notification: NotificationData, config: ServerConfig): SendResult {
        return sendBytes(encodePayload(notification), config)
    }

    override fun send(sms: SmsData, config: ServerConfig): SendResult {
        return sendBytes(encodeSmsPayload(sms), config)
    }

    private fun sendBytes(payload: ByteArray, config: ServerConfig): SendResult {
        if (!config.isValid) {
            return SendResult.Failure(IllegalArgumentException("Invalid server endpoint"))
        }

        return runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(
                    InetSocketAddress(config.host, config.port),
                    CONNECT_TIMEOUT_MS,
                )
                socket.getOutputStream().use { output ->
                    output.write(payload)
                    output.flush()
                }
            }
        }.fold(
            onSuccess = { SendResult.Success },
            onFailure = { SendResult.Failure(it) },
        )
    }

    internal fun encodePayload(notification: NotificationData): ByteArray = buildString {
        append("{\"title\":")
        appendJsonString(notification.title)
        append(",\"content\":")
        appendJsonString(notification.content)
        append("}\n")
    }.toByteArray(Charsets.UTF_8)

    internal fun encodeSmsPayload(sms: SmsData): ByteArray = buildString {
        append("{\"msg\":")
        appendJsonString(sms.msg)
        append(",\"sms_code\":")
        appendJsonString(sms.smsCode)
        append("}\n")
    }.toByteArray(Charsets.UTF_8)

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"', '\\', '/' -> append('\\').append(char)
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) appendUnicodeEscape(char) else append(char)
            }
        }
        append('"')
    }

    private fun StringBuilder.appendUnicodeEscape(char: Char) {
        append("\\u")
        append(char.code.toString(16).padStart(4, '0'))
    }
}
