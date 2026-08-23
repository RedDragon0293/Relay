package cn.reddragon.relay.hook

import cn.reddragon.relay.RelayModule.Companion.log
import cn.reddragon.relay.module
import cn.reddragon.relay.model.SmsData
import cn.reddragon.relay.tcp.SmsDispatcher
import cn.reddragon.relay.util.PreferenceHelper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Hooks the system MMS app's verification-code extraction helper. */
object OtpHooker {
    private const val MMS_PACKAGE = "com.android.mms"
    private const val HOOK_ID = "sms-otp-c1054u0-n"

    @Volatile
    private var preferences: PreferenceHelper? = null

    @Volatile
    private var hooked = false

    @Volatile
    private var dispatcher = createDispatcher()

    //private lateinit var dexKitBridge: DexKitBridge

    //private lateinit var sourceDir: String

    /** Initial installation with explicitly injected remote preferences. */
    fun hook(
        param: XposedModuleInterface.PackageReadyParam,
        preferences: PreferenceHelper,
    ) {
        this.preferences = preferences
        if (param.packageName != MMS_PACKAGE) {
            log("Skipping SMS hook for package ${param.packageName}")
            return
        }
        //dexKitBridge = DexKitBridge.create(param.applicationInfo.sourceDir)
        //sourceDir = param.applicationInfo.sourceDir

        install(param.classLoader)
    }

    /** Replace this process's hook after API 102 hot reload. */
    fun reinstall(
        classLoader: ClassLoader,
        preferences: PreferenceHelper,
        oldHandles: List<XposedInterface.HookHandle>,
    ) {
        this.preferences = preferences

        val smsHandles = oldHandles.filter { it.id == HOOK_ID }

        if (oldHandles.isEmpty()) {
            install(classLoader)
            return
        }

        var replaced = 0
        smsHandles.forEach { handle ->
            runCatching {
                handle.replaceHook(createHooker(classLoader))
            }
                .onSuccess { replaced++ }
                .onFailure { log("Failed to replace SMS hook after hot reload", it) }
        }

        log("SMS hot reload: replaced $replaced hook(s)")
    }

    fun shutdownForHotReload() {
        dispatcher.shutdownNow()
        hooked = false
    }

    private fun install(classLoader: ClassLoader) {
        if (hooked) {
            log("SMS hook already installed; skip")
            return
        }

        try {
            val method = findVerificationMethod(classLoader)
            module.hook(method)
                .setId(HOOK_ID)
                .intercept(createHooker(classLoader))
            hooked = true
            log("Hooked SMS verification method ${method.declaringClass.name}#${method.name}")
        } catch (e: Throwable) {
            log("Error trying to hook mms", e)
        }
    }

    private fun findVerificationMethod(classLoader: ClassLoader): Method {
        runCatching {
            System.loadLibrary("dexkit")
        }.onFailure {
            log("Unable to load DexKit native library", it)
        }

        DexKitBridge.create(classLoader, false).use { bridge ->
            return bridge.findClass {
                searchPackages("com.android.mms.ui")
                matcher { usingStrings("handleVerificationCodeReceived: sdk is null") }
            }.findMethod {
                matcher {
                    returnType = "com.miui.smsextra.sdk.ItemExtra"
                    modifiers = Modifier.PUBLIC or Modifier.STATIC
                    paramTypes(
                        "android.content.Context",
                        "java.lang.String",
                        "int",
                        "java.lang.String",
                        "java.lang.String",
                    )
                }
            }.single().getMethodInstance(classLoader)
        }
    }

    private fun createHooker(cl: ClassLoader) = XposedInterface.Hooker { chain ->
        val itemExtra = chain.proceed()
        if (itemExtra != null) {
            var method: Method? = null
            runCatching {
                log("尝试使用DexKit获取ItemExtra Class。")

                runCatching {
                    System.loadLibrary("dexkit")
                }.onFailure {
                    log("Unable to load DexKit native library", it)
                }

                method = DexKitBridge.create(cl, false).use { bridge ->
                    return@use bridge.findClass {
                        matcher {
                            className(itemExtra.javaClass.name)
                        }
                    }.findMethod {
                        matcher {
                            name("getOTP")
                        }
                    }.single().getMethodInstance(cl)
                }
            }.onFailure {
                log("无法使用DexKit获取ItemExtra Class。转为使用反射。", it)

                method = cl.loadClass("com.miui.smsextra.sdk.ItemExtra")
                    .getDeclaredMethod("getOTP")
            }
            runCatching {
                val otp = module.getInvoker(method!!)
                    .setType(XposedInterface.Invoker.Type.ORIGIN)
                    .invoke(itemExtra) as String
                val raw = chain.getArg(4) as String
                if (otp.isNotBlank() && raw.isNotBlank()) {
                    sendSms(otp, raw)
                } else {
                    log("SMS verification payload is empty; skipped")
                }
            }.onFailure { log("Unable to extract SMS verification payload", it) }
        }
        itemExtra
    }

    private fun sendSms(otp: String, raw: String) {
        val config = preferences?.getOtpServerConfig() ?: return
        if (!config.isValid) {
            log("Invalid server config; SMS skipped")
            return
        }

        dispatcher.enqueue(
            sms = SmsData(msg = raw, smsCode = otp),
            config = config,
            sourcePackage = MMS_PACKAGE,
        )
    }

    private fun createDispatcher() = SmsDispatcher(
        logger = { message, error -> log(message, error) },
    )
}
