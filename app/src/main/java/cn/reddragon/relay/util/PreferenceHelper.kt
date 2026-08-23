package cn.reddragon.relay.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import cn.reddragon.relay.tcp.ServerConfig

/**
 * Relay 配置的统一读写入口。
 *
 * 配置的权威数据源是 libxposed remote preferences（存储在 LSPosed 框架数据库中，
 * system_server 注入进程通过 getRemotePreferences 读取同一份数据，实时同步）。
 * 框架未连接时退化为宿主本地 SharedPreferences，保证 UI 不崩溃；
 * 此时配置不会生效，由界面提示用户模块未激活。
 */
class PreferenceHelper(
    private val prefs: SharedPreferences,
) {
    fun getNotificationServerConfig() = ServerConfig(
        host = getServerHost(),
        port = getNotificationPort()
    )

    fun getOtpServerConfig() = ServerConfig(
        host = getServerHost(),
        port = getOtpPort()
    )

    fun getServerHost() = prefs.getString(KEY_SERVER_HOST, DEFAULT_SERVER_HOST) ?: DEFAULT_SERVER_HOST

    fun getNotificationPort() = prefs.getInt(KEY_NOTIFICATION_PORT, DEFAULT_SERVER_PORT)

    fun getOtpPort() = prefs.getInt(KEY_OTP_PORT, DEFAULT_SERVER_PORT)

    /** SharedPreferences 的 StringSet 可能是内部可变集合，始终返回独立快照。 */
    fun getTargetPackages(): Set<String> =
        prefs.getStringSet(KEY_TARGET_PACKAGES, emptySet())?.toSet().orEmpty()

    /** 仅持久化完整有效的服务器配置。 */
    fun setServerConfig(host: String, notificationPort: Int, otpPort: Int): Boolean {
        if (!ServerConfig.isPortValid(notificationPort) || !ServerConfig.isPortValid(otpPort)) return false

        prefs.edit {
            putString(KEY_SERVER_HOST, host)
            putInt(KEY_NOTIFICATION_PORT, notificationPort)
            putInt(KEY_OTP_PORT, otpPort)
        }
        return true
    }

    fun setTargetPackages(packages: Set<String>) {
        prefs.edit { putStringSet(KEY_TARGET_PACKAGES, packages.toSet()) }
    }

    companion object {
        const val PREFS_NAME = "relay_settings"
        const val DEFAULT_SERVER_HOST = "192.168.1.100"
        const val DEFAULT_SERVER_PORT = 8080

        internal const val KEY_SERVER_HOST = "server_host"
        internal const val KEY_NOTIFICATION_PORT = "notification_port"
        internal const val KEY_OTP_PORT = "otp_port"
        internal const val KEY_TARGET_PACKAGES = "target_packages"

        fun from(context: Context): PreferenceHelper {
            val remote = FrameworkConnector.getRemotePreferences(PREFS_NAME)
            return if (remote != null) {
                PreferenceHelper(remote)
            } else {
                PreferenceHelper(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                )
            }
        }
    }
}
