package cn.reddragon.relay.model

/**
 * Captured SMS verification data sent over the relay TCP channel.
 *
 * The property names intentionally describe the wire fields: [msg] is the
 * original SMS text and [smsCode] is the extracted verification code.
 */
data class SmsData(
    val msg: String,
    val smsCode: String,
)
