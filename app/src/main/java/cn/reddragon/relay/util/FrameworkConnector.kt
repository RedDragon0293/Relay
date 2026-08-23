package cn.reddragon.relay.util

import android.content.SharedPreferences
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 与 Xposed 框架（LSPosed）的连接状态、remote preferences 与热重载入口。
 *
 * 宿主进程通过 [XposedServiceHelper] 接收框架下发的服务 binder；
 * 仅当框架已连接且支持 remote capability 时 [isConnected] 为 true。
 * 模块未激活/框架不可用时 UI 据此隐藏应用列表与配置编辑项。
 *
 * API 102 热重载：通过 [getRunningTargets] 查询被模块 hook 的进程，
 * [hotReload] 请求框架加载模块新代码并原子替换 hooks，无需重启系统。
 */
object FrameworkConnector : XposedServiceHelper.OnServiceListener {

    private val _service = AtomicReference<XposedService?>(null)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _targets = MutableStateFlow<List<HookedTarget>>(emptyList())
    val targets: StateFlow<List<HookedTarget>> = _targets.asStateFlow()

    /** 热重载 UI 状态：进行中的进程 + 最近一次结果。 */
    data class HotReloadUiState(
        val reloadingProcess: String? = null,
        val lastResult: Pair<String, HotReloadResult>? = null,
    )

    private val _hotReloadState = MutableStateFlow(HotReloadUiState())
    val hotReloadState: StateFlow<HotReloadUiState> = _hotReloadState.asStateFlow()

    /** 必须且只能调用一次（由 [cn.reddragon.relay.RelayApplication] 调用）。 */
    fun init() {
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        val remoteCapable = runCatching {
            service.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L
        }.getOrDefault(false)
        if (!remoteCapable) return

        _service.set(service)
        _isConnected.value = true
        refreshTargets()
    }

    override fun onServiceDied(service: XposedService) {
        _service.set(null)
        _isConnected.value = false
        _targets.value = emptyList()
    }

    /** 返回框架的 remote preferences；未连接或不可用时返回 null。 */
    fun getRemotePreferences(name: String): SharedPreferences? {
        val service = _service.get() ?: return null
        return runCatching { service.getRemotePreferences(name) }.getOrNull()
    }

    /** 查询当前被模块 hook 的进程列表（API 102）。 */
    fun refreshTargets() {
        val service = _service.get() ?: run {
            _targets.value = emptyList()
            return
        }
        if (service.apiVersion < XposedService.API_102) return
        _targets.value = runCatching { service.getRunningTargets() }
            .getOrDefault(emptyList())
    }

    /** 请求热重载指定进程（API 102），结果通过 [hotReloadState] 异步更新。 */
    fun hotReload(target: HookedTarget) {
        val service = _service.get() ?: return
        if (service.apiVersion < XposedService.API_102) return

        _hotReloadState.value = HotReloadUiState(reloadingProcess = target.processName)
        runCatching {
            service.hotReloadModule(target, null) { reloadedTarget, result ->
                _hotReloadState.value = HotReloadUiState(
                    reloadingProcess = null,
                    lastResult = reloadedTarget.processName to result,
                )
                refreshTargets()
            }
        }.onFailure { e ->
            _hotReloadState.value = HotReloadUiState(
                reloadingProcess = null,
                lastResult = target.processName to HotReloadResult(
                    HotReloadResult.Status.FAILED,
                    "请求失败: ${e.message}",
                ),
            )
        }
    }
}
