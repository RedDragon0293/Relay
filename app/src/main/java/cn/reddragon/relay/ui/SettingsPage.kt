package cn.reddragon.relay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.reddragon.relay.util.FrameworkConnector
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult

@Composable
fun SettingsPage(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val serverHost by viewModel.serverHost.collectAsStateWithLifecycle()
    val notificationPort by viewModel.notificationPort.collectAsStateWithLifecycle()
    val otpPort by viewModel.otpPort.collectAsStateWithLifecycle()
    val frameworkConnected by FrameworkConnector.isConnected.collectAsStateWithLifecycle()
    val targets by FrameworkConnector.targets.collectAsStateWithLifecycle()
    val hotReloadState by FrameworkConnector.hotReloadState.collectAsStateWithLifecycle()

    // 进入页面时刷新一次运行中的目标进程；连接状态变化时同样刷新
    LaunchedEffect(frameworkConnected) {
        if (frameworkConnected) FrameworkConnector.refreshTargets()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 模块未激活时隐藏服务器配置编辑项（配置不会生效）
        if (frameworkConnected) {
            Text(
                text = "服务器配置",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            OutlinedTextField(
                value = serverHost,
                onValueChange = viewModel::onServerHostChanged,
                label = { Text("服务器 IP 地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = notificationPort,
                onValueChange = viewModel::onNotificationPortChanged,
                label = { Text("通知端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = otpPort,
                onValueChange = viewModel::onOtpPortChanged,
                label = { Text("验证码端口") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            HotReloadSection(
                targets = targets,
                hotReloadState = hotReloadState,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        /*Text(
            text = "使用说明",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "1. 在「应用列表」中选择要捕获通知的目标应用",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "2. 配置服务器地址和端口",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "3. 在 LSPosed Manager 中启用模块，scope 勾选「系统框架」(system)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "4. 重启设备使 Hook 生效；之后更新模块可通过热重载立即生效",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }*/
    }
}

/** API 102 热重载区块：列出被模块 hook 的进程并提供热重载按钮。 */
@Composable
private fun HotReloadSection(
    targets: List<HookedTarget>,
    hotReloadState: FrameworkConnector.HotReloadUiState,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "模块热重载",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { FrameworkConnector.refreshTargets() }) {
                    Text("刷新")
                }
            }

            if (targets.isEmpty()) {
                Text(
                    text = "没有正在运行的目标进程\n（system_server 未加载模块或尚未重启）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                targets.forEach { target ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = target.processName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = "${target.state.label()} · 已加载版本 ${target.loadedVersionCode}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            enabled = hotReloadState.reloadingProcess == null,
                            onClick = { FrameworkConnector.hotReload(target) },
                        ) {
                            Text("热重载")
                        }
                    }
                }
            }

            hotReloadState.reloadingProcess?.let { process ->
                Text(
                    text = "正在重载 $process ...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            hotReloadState.lastResult?.let { (process, result) ->
                Text(
                    text = "$process: ${result.status().label()}" +
                        (result.message()?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (result.status()) {
                        HotReloadResult.Status.SUCCEEDED -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun HookedTarget.State.label(): String = when (this) {
    HookedTarget.State.UP_TO_DATE -> "已是最新"
    HookedTarget.State.STALE -> "等待重载"
    HookedTarget.State.RELOADING -> "重载中"
    HookedTarget.State.FAILED -> "上次重载失败"
}

private fun HotReloadResult.Status.label(): String = when (this) {
    HotReloadResult.Status.SUCCEEDED -> "热重载成功"
    HotReloadResult.Status.FAILED -> "热重载失败"
    HotReloadResult.Status.UNSUPPORTED -> "目标不支持热重载"
    HotReloadResult.Status.IN_PROGRESS -> "已有重载正在进行"
    HotReloadResult.Status.PROCESS_DIED -> "目标进程已退出"
}
