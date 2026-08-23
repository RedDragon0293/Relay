package cn.reddragon.relay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.reddragon.relay.util.FrameworkConnector

private data class TabItem(
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: SettingsViewModel = viewModel()) {
    val frameworkConnected by FrameworkConnector.isConnected.collectAsState()
    val tabs = remember {
        listOf(
            TabItem("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
            TabItem("应用列表", Icons.Filled.Apps, Icons.Outlined.Apps),
        )
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var appSearchQuery by rememberSaveable { mutableStateOf("") }
    var appSortModeName by rememberSaveable { mutableStateOf(AppSortMode.APP_NAME.name) }
    var appSortDescending by rememberSaveable { mutableStateOf(false) }
    val appSortMode = AppSortMode.entries.firstOrNull { it.name == appSortModeName }
        ?: AppSortMode.APP_NAME
    val appListState = rememberLazyListState()

    // 模块未激活时隐藏应用列表：只保留设置页
    val visibleTabs = if (frameworkConnected) tabs else listOf(tabs[0])
    val currentTab = if (selectedTab < visibleTabs.size) selectedTab else 0

    // AppList 页返回手势：搜索框有内容时先清空搜索；为空时返回主界面（设置页）而非退出应用
    BackHandler(enabled = currentTab == 1) {
        if (appSearchQuery.isNotEmpty()) {
            appSearchQuery = ""
        } else {
            selectedTab = 0
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Relay") },
                actions = {
                    RelayStatusIndicator(connected = frameworkConnected)
                },
            )
        },
        bottomBar = {
            if (visibleTabs.size > 1) {
                NavigationBar {
                    visibleTabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = currentTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                Icon(
                                    imageVector = if (currentTab == index) {
                                        tab.selectedIcon
                                    } else {
                                        tab.unselectedIcon
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(tab.title) },
                        )
                    }
                }
            }
        },
    ) { paddingValues ->
        when (currentTab) {
            0 -> SettingsPage(
                viewModel = viewModel,
                modifier = Modifier.padding(paddingValues),
            )
            1 -> AppListPage(
                viewModel = viewModel,
                searchQuery = appSearchQuery,
                onSearchQueryChanged = { appSearchQuery = it },
                sortMode = appSortMode,
                onSortModeChanged = { appSortModeName = it.name },
                sortDescending = appSortDescending,
                onSortDescendingChanged = { appSortDescending = it },
                listState = appListState,
                modifier = Modifier.padding(paddingValues),
            )
        }
    }
}

/** 顶部状态指示：显示当前模块是否已激活（框架已连接且支持 remote preferences）。 */
@Composable
private fun RelayStatusIndicator(connected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (connected) ActiveColor else InactiveColor),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (connected) "模块已激活" else "模块未激活",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ActiveColor = Color(0xFF4CAF50)
private val InactiveColor = Color(0xFFF44336)
