package cn.reddragon.relay.hook

import android.app.Notification
import cn.reddragon.relay.RelayModule.Companion.log
import cn.reddragon.relay.module
import cn.reddragon.relay.model.NotificationData
import cn.reddragon.relay.tcp.NotificationDispatcher
import cn.reddragon.relay.util.PreferenceHelper
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Modifier

/**
 * Hook 系统 NotificationManagerService，捕获通知后直接通过 TCP 发送给服务器。
 *
 * 发送在 system_server 注入进程内异步执行（单线程有界队列），不阻塞 NMS。
 * 配置（目标应用、服务器地址）来自 remote preferences，与 App 进程共享。
 *
 * API 102 热重载支持：
 * - [hook] 幂等，同一代实例不会重复安装
 * - [reinstall] 热重载后由新代码调用，通过 replaceHook 原子替换旧 hooker
 * - [shutdownForHotReload] 由旧代码在 onHotReloading 中调用，停止发送线程
 */
object NotificationHooker {

    @Volatile
    private var preferences: PreferenceHelper? = null

    @Volatile
    private var hooked = false

    /** AOSP / 小米 的 NMS 类全限定名候选列表 */
    private val NMS_CLASS_NAMES = listOf(
        "com.android.server.notification.NotificationManagerService",
    )

    /** Hook 目标方法名候选列表 */
    private val HOOK_METHOD_NAMES = listOf(
        //"enqueueNotificationWithTag",
        "enqueueNotificationInternal",
    )

    /** 单工作线程、有界内存队列的 TCP 发送调度器。 */
    private val dispatcher = NotificationDispatcher(
        logger = { message, error -> log(message, error) },
    )

    /** 初始安装（onSystemServerStarting）。幂等：重复调用只记录日志。 */
    fun hook(classLoader: ClassLoader, preferences: PreferenceHelper) {
        this.preferences = preferences
        install(classLoader)
    }

    /**
     * 热重载后由新代码调用：优先用 replaceHook 原子替换旧 hooks；
     * 无旧 hooks（异常路径）时回退到全新安装。
     */
    fun reinstall(
        classLoader: ClassLoader,
        preferences: PreferenceHelper,
        oldHandles: List<XposedInterface.HookHandle>,
    ) {
        this.preferences = preferences

        if (oldHandles.isEmpty()) {
            log("No old hook handles from hot reload; reinstalling hooks")
            install(classLoader)
            return
        }

        var replaced = 0
        for (handle in oldHandles) {
            runCatching { handle.replaceHook(NotificationHooker.createHooker()) }
                .onSuccess { replaced++ }
                .onFailure { e ->
                    log("Failed to replace hook after hot reload", e)
                }
        }
        log("Hot reload: replaced $replaced/${oldHandles.size} hooks")
    }

    /** 热重载前由旧代码调用：停止发送线程并丢弃队列中的任务。 */
    fun shutdownForHotReload() {
        dispatcher.shutdownNow()
    }

    private fun install(classLoader: ClassLoader) {
        if (hooked) {
            log("Hooks already installed; skip")
            return
        }

        val nmsClass = NMS_CLASS_NAMES.firstNotNullOfOrNull { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()
        } ?: run {
            log("NotificationManagerService class not found — module will not work")
            return
        }

        var installed = 0
        for (methodName in HOOK_METHOD_NAMES) {
            val methods = runCatching {
                nmsClass.declaredMethods
                    .filter { it.name == methodName }
                    .filter { Modifier.isPrivate(it.modifiers) }
            }.getOrDefault(emptyList())


            runCatching {
                val method = methods.first()
                module.hook(method).intercept(createHooker())
                installed++
                log("Hooked ${nmsClass.name}#${method.name}(${method.parameterTypes.joinToString { it.simpleName }})")
            }.onFailure { e ->
                log("Failed to hook ${nmsClass.name}#${methodName}", e)
            }

        }

        hooked = installed > 0
        if (!hooked) {
            log("No NotificationManagerService method hooked — module will not work")
        }
    }

    /** 每次调用创建新的 hooker：热重载时新代码必须用新 classloader 下的 hooker 替换旧 hook。 */
    private fun createHooker(): XposedInterface.Hooker = XposedInterface.Hooker { chain ->
        // 先执行原方法，让通知正常显示
        val result = chain.proceed()

        // 提取通知数据并异步发送，不影响系统
        runCatching {
            val args = chain.args
            val notification = extractNotification(args)
            val pkg = extractPackageName(args)

            if (notification != null && pkg != null) {
                handleNotification(pkg, notification)
            }
        }.onFailure { e ->
            log("Failed to handle notification", e)
        }

        result
    }

    /**
     * 从方法参数中提取 Notification 对象。
     * enqueueNotificationInternal(
     *     String pkg,       // 应用包名
     *     String opPkg,     // 操作包名（通常与 pkg 相同）
     *     int callingUid,   // 调用方 UID
     *     int callingPid,   // 调用方 PID
     *     String tag,       // 通知 tag，可为空
     *     int id,           // 通知 ID
     *     Notification notification, // 通知对象
     *     int incomingUserId // 用户 ID
     *     )
     *
     * enqueueNotificationWithTag(String str, String str2, String str3, int i, Notification notification, int i2)
     */
    private fun extractNotification(args: List<*>): Notification? {
        return args.firstOrNull { it is Notification } as? Notification
    }

    /**
     * 从方法参数中提取包名（第一个 String 参数）。
     */
    private fun extractPackageName(args: List<*>): String? {
        // pkg 通常是第一个 String 参数
        return args.firstOrNull { it is String && it.contains(".") } as? String
    }

    /**
     * 判断是否为目标应用；是则通过调度器直接向服务器发送 TCP 消息。
     */
    private fun handleNotification(
        pkg: String,
        notification: Notification,
    ) {
        val prefs = preferences ?: return
        val targetPackages = prefs.getTargetPackages()
        if (targetPackages.isEmpty() || pkg !in targetPackages) return

        val config = prefs.getNotificationServerConfig()
        if (!config.isValid) {
            log("Invalid server config; notification skipped: pkg=$pkg")
            return
        }

        val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            .orEmpty()
        val content = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            .orEmpty()

        log("Captured notification: pkg=$pkg")

        dispatcher.enqueue(
            notification = NotificationData(title = title, content = content),
            config = config,
            sourcePackage = pkg,
            onComplete = {},
        )
    }
}
