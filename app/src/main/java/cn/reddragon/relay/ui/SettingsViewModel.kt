package cn.reddragon.relay.ui

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.reddragon.relay.util.FrameworkConnector
import cn.reddragon.relay.util.PreferenceHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isInstalled: Boolean = true,
    val firstInstallTime: Long = 0L,
    val lastUpdateTime: Long = 0L,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    /** 每次调用实时解析数据源：框架连接后为 remote preferences。 */
    private fun preferences(): PreferenceHelper = PreferenceHelper.from(getApplication())

    private val _serverHost = MutableStateFlow("")
    val serverHost: StateFlow<String> = _serverHost.asStateFlow()

    private val _notificationPort = MutableStateFlow("")
    val notificationPort: StateFlow<String> = _notificationPort.asStateFlow()

    private val _otpPort = MutableStateFlow("")
    val otpPort: StateFlow<String> = _otpPort.asStateFlow()

    private val _targetPackages = MutableStateFlow<Set<String>>(emptySet())
    val targetPackages: StateFlow<Set<String>> = _targetPackages.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _appListError = MutableStateFlow<String?>(null)
    val appListError: StateFlow<String?> = _appListError.asStateFlow()

    private val _hasLoadedApps = MutableStateFlow(false)
    val hasLoadedApps: StateFlow<Boolean> = _hasLoadedApps.asStateFlow()

    private var loadAppsJob: Job? = null
    private var loadRequestId = 0

    init {
        reloadFromPreferences()
        loadInstalledApps()
        // 框架连接（或重连）后，配置可能已在 system_server 侧变更，重新加载
        viewModelScope.launch {
            FrameworkConnector.isConnected.collect { connected ->
                if (connected) reloadFromPreferences()
            }
        }
    }

    /** 从当前数据源（连接框架后为 remote preferences）重载配置。 */
    private fun reloadFromPreferences() {
        val prefs = preferences()
        _serverHost.value = prefs.getServerHost()
        _notificationPort.value = prefs.getNotificationPort().toString()
        _targetPackages.value = prefs.getTargetPackages()
    }

    private fun loadInstalledApps() {
        val requestId = ++loadRequestId
        loadAppsJob?.cancel()
        loadAppsJob = viewModelScope.launch {
            _isRefreshing.value = true
            _appListError.value = null
            try {
                val apps = withContext(Dispatchers.IO) {
                    val application = getApplication<Application>()
                    val pm = application.packageManager
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .asSequence()
                        .filter { it.packageName != application.packageName }
                        .map { app ->
                            val packageInfo = runCatching {
                                pm.getPackageInfoCompat(app.packageName)
                            }.getOrNull()
                            AppInfo(
                                packageName = app.packageName,
                                appName = runCatching {
                                    pm.getApplicationLabel(app).toString()
                                }.getOrDefault(app.packageName),
                                firstInstallTime = packageInfo?.firstInstallTime ?: 0L,
                                lastUpdateTime = packageInfo?.lastUpdateTime ?: 0L,
                            )
                        }
                        .sortedBy { it.appName.lowercase() }
                        .toList()
                }
                if (requestId == loadRequestId) {
                    _installedApps.value = apps
                    _hasLoadedApps.value = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (requestId == loadRequestId) {
                    _appListError.value = "加载应用列表失败，请重试"
                }
            } finally {
                if (requestId == loadRequestId) _isRefreshing.value = false
            }
        }
    }

    fun refreshApps() {
        loadInstalledApps()
    }

    fun onServerHostChanged(host: String) {
        _serverHost.value = host
        saveServerConfig()
    }

    fun onNotificationPortChanged(port: String) {
        _notificationPort.value = port
        saveServerConfig()
    }

    fun onOtpPortChanged(port: String) {
        _otpPort.value = port
        saveServerConfig()
    }

    private fun saveServerConfig() {
        val port1 = _notificationPort.value.toIntOrNull() ?: return
        val port2 = _otpPort.value.toIntOrNull() ?: return
        preferences().setServerConfig(_serverHost.value, port1, port2)
    }

    fun addTargetPackage(packageName: String) {
        updateTargetPackages(_targetPackages.value + packageName)
    }

    fun removeTargetPackage(packageName: String) {
        updateTargetPackages(_targetPackages.value - packageName)
    }

    fun toggleTargetPackage(packageName: String) {
        val current = _targetPackages.value
        updateTargetPackages(if (packageName in current) {
            current - packageName
        } else {
            current + packageName
        })
    }

    private fun updateTargetPackages(packages: Set<String>) {
        _targetPackages.value = packages
        preferences().setTargetPackages(packages)
    }

    /** 获取目标 App 的显示名称 */
    fun getAppName(packageName: String): String {
        return _installedApps.value.find { it.packageName == packageName }?.appName ?: packageName
    }

    /** 未安装到目标列表的 App */
    fun getAvailableApps(): List<AppInfo> {
        return _installedApps.value.filter { it.packageName !in _targetPackages.value }
    }
}

//@Suppress("DEPRECATION")
private fun PackageManager.getPackageInfoCompat(packageName: String) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        getPackageInfo(packageName, 0)
    }
