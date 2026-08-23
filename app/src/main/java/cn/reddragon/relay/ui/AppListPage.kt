package cn.reddragon.relay.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListPage(
    searchQuery: String,
    onSearchQueryChanged: (String) -> Unit,
    sortMode: AppSortMode,
    onSortModeChanged: (AppSortMode) -> Unit,
    sortDescending: Boolean,
    onSortDescendingChanged: (Boolean) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel,
) {
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val targetPackages by viewModel.targetPackages.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.appListError.collectAsStateWithLifecycle()
    val hasLoadedApps by viewModel.hasLoadedApps.collectAsStateWithLifecycle()

    val filteredApps = remember(
        installedApps,
        searchQuery,
        targetPackages,
        hasLoadedApps,
        sortMode,
        sortDescending,
    ) {
        buildAppList(
            installedApps = installedApps,
            targetPackages = targetPackages,
            searchQuery = searchQuery,
            includeMissingSelected = hasLoadedApps,
            sortMode = sortMode,
            sortDescending = sortDescending,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索应用…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
            )
            Spacer(modifier = Modifier.width(4.dp))
            AppSortButton(
                sortMode = sortMode,
                onSortModeChanged = onSortModeChanged,
                sortDescending = sortDescending,
                onSortDescendingChanged = onSortDescendingChanged,
                listState = listState,
            )
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refreshApps,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (filteredApps.isEmpty()) {
                    item(key = "empty-state") {
                        EmptyAppListState(
                            message = when {
                                errorMessage != null -> errorMessage.orEmpty()
                                isRefreshing -> "正在加载应用…"
                                else -> "未找到匹配的应用"
                            },
                            showRetry = errorMessage != null,
                            onRetry = viewModel::refreshApps,
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                } else {
                    if (errorMessage != null) {
                        item(key = "load-error") {
                            AppListErrorCard(
                                message = errorMessage.orEmpty(),
                                onRetry = viewModel::refreshApps,
                            )
                        }
                    }

                    items(filteredApps, key = { it.packageName }) { app ->
                        val isSelected = app.packageName in targetPackages
                        AppListItem(
                            app = app,
                            isSelected = isSelected,
                            onToggle = { viewModel.toggleTargetPackage(app.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSortButton(
    sortMode: AppSortMode,
    onSortModeChanged: (AppSortMode) -> Unit,
    sortDescending: Boolean,
    onSortDescendingChanged: (Boolean) -> Unit,
    listState: LazyListState,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Box {
        IconButton(onClick = { menuExpanded = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription =
                    "调整排序（当前：${sortMode.label}${if (sortDescending) "倒序" else "正序"}）",
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            AppSortMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        menuExpanded = false
                        if (mode != sortMode) {
                            onSortModeChanged(mode)
                            coroutineScope.launch { listState.scrollToItem(0) }
                        }
                    },
                    leadingIcon = {
                        if (mode == sortMode) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                            )
                        } else {
                            Spacer(modifier = Modifier.size(24.dp))
                        }
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("倒序") },
                onClick = {
                    menuExpanded = false
                    onSortDescendingChanged(!sortDescending)
                    coroutineScope.launch { listState.scrollToItem(0) }
                },
                leadingIcon = {
                    Checkbox(
                        checked = sortDescending,
                        onCheckedChange = null,
                    )
                },
            )
        }
    }
}

@Composable
private fun EmptyAppListState(
    message: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showRetry) TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun AppListErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun AppListItem(
    app: AppInfo,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                role = Role.Checkbox,
                onClick = onToggle,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (isSelected) CardDefaults.outlinedCardBorder() else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                packageName = app.packageName,
                isInstalled = app.isInstalled,
                modifier = Modifier.size(40.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (app.isInstalled) app.packageName else "未安装",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AppIcon(
    packageName: String,
    isInstalled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val iconSizePx = remember(density) { with(density) { 40.dp.roundToPx() } }
    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        packageName,
        isInstalled,
        iconSizePx,
    ) {
        value = if (isInstalled) {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.packageManager
                        .getApplicationIcon(packageName)
                        .toBitmap(iconSizePx, iconSizePx)
                }.getOrNull()
            }
        } else {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
        )
    } else {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null) {
        if (bitmap.width == width && bitmap.height == height) return bitmap
        return bitmap.scale(width, height)
    }

    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}
