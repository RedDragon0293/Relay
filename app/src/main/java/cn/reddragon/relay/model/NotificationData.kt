package cn.reddragon.relay.model

/**
 * 捕获到的通知数据，通过 TCP 以 JSON 发送。
 */
data class NotificationData(
    val title: String,
    val content: String,
)
