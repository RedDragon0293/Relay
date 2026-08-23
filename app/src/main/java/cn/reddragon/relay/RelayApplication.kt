package cn.reddragon.relay

import android.app.Application
import cn.reddragon.relay.util.FrameworkConnector

/**
 * 应用入口：注册 Xposed 框架服务监听，
 * 使宿主进程能够通过 remote preferences 与 system_server 中的模块共享配置。
 */
class RelayApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FrameworkConnector.init()
    }
}
