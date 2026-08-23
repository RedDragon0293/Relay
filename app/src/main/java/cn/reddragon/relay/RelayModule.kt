package cn.reddragon.relay

import android.util.Log
import cn.reddragon.relay.hook.NotificationHooker
import cn.reddragon.relay.hook.OtpHooker
import cn.reddragon.relay.util.PreferenceHelper
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal lateinit var module: RelayModule

class RelayModule : XposedModule() {
    private var remotePreferences: PreferenceHelper? = null
    private var loadedProcessName: String = ""
    private var loadedInSystemServer: Boolean = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        loadedProcessName = param.processName
        loadedInSystemServer = param.isSystemServer
        log("v${BuildConfig.VERSION_NAME} loaded in ${param.processName}")

        if (frameworkProperties and PROP_CAP_REMOTE == 0L) {
            log("Framework does not expose remote preferences; capture is disabled")
            return
        }

        remotePreferences = runCatching {
            PreferenceHelper(getRemotePreferences(PreferenceHelper.PREFS_NAME))
        }.onFailure { error ->
            log("Failed to initialize remote preferences", error)
        }.getOrNull()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        val preferences = remotePreferences ?: run {
            log("Remote preferences are unavailable; skipping NotificationManagerService hook")
            return
        }

        log("Hooking system framework (NotificationManagerService)...")
        NotificationHooker.hook(param.classLoader, preferences)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if ("com.android.mms" != param.packageName) return

        val preferences = remotePreferences ?: run {
            log("Remote preferences are unavailable; skipping SMS hook")
            return
        }
        OtpHooker.hook(param, preferences)
    }

    /**
     * API 102 热重载：旧代码侧回调。先停止本代代码的 TCP 发送线程，
     * 再同意重载；hooks 由框架收集后交给新代码原子替换。
     */
    override fun onHotReloading(param: HotReloadingParam): Boolean {
        log("Hot reload requested; stopping TCP dispatcher and accepting")

        if (loadedInSystemServer) {
            NotificationHooker.shutdownForHotReload()
        } else if (loadedProcessName == "com.android.mms") {
            OtpHooker.shutdownForHotReload()
        }
        return true
    }

    /**
     * API 102 热重载：新代码侧回调。
     * 框架不会重放 onModuleLoaded/onSystemServerStarting，需要在此重新初始化
     * remote preferences 并替换/重装 NMS hooks。
     */
    override fun onHotReloaded(param: HotReloadedParam) {
        module = this
        log("Module hot reloaded in ${param.processName}")

        val preferences = runCatching {
            PreferenceHelper(getRemotePreferences(PreferenceHelper.PREFS_NAME))
        }.onFailure { error ->
            log("Failed to reinitialize remote preferences after hot reload", error)
        }.getOrNull() ?: run {
            log("Remote preferences unavailable after hot reload; skipping hook reinstall")
            return
        }
        remotePreferences = preferences

        if (param.isSystemServer) {
            // 热重载后系统类由 boot classloader 加载，用它重新定位 NMS
            val systemClassLoader = android.app.Notification::class.java.classLoader
                ?: ClassLoader.getSystemClassLoader()
            NotificationHooker.reinstall(
                classLoader = systemClassLoader,
                preferences = preferences,
                oldHandles = param.oldHookHandles,
            )
        } else if (param.processName == "com.android.mms") {
            val smsClassLoader = param.oldHookHandles
                .mapNotNull { it.executable.declaringClass.classLoader }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }?.key
                ?: Thread.currentThread().contextClassLoader
                ?: ClassLoader.getSystemClassLoader()

            OtpHooker.reinstall(
                classLoader = smsClassLoader,
                preferences = preferences,
                oldHandles = param.oldHookHandles,
            )
        }
    }

    companion object {
        const val TAG = "RelayModule"

        fun log(
            msg: String,
            t: Throwable? = null,
        ) {
            if (t != null) {
                module.log(Log.ERROR, TAG, msg, t)
            } else {
                module.log(Log.INFO, TAG, msg)
            }
        }

        fun log(priority: Int, msg: String) {
            module.log(priority, TAG, msg)
        }
    }
}
