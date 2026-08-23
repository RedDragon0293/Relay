package cn.reddragon.relay.ui

enum class AppSortMode(val label: String) {
    APP_NAME("应用名"),
    PACKAGE_NAME("包名"),
    INSTALL_TIME("安装时间"),
    UPDATE_TIME("更新时间"),
}

/** 合并已安装应用与仍被选中的缺失应用，再执行搜索和排序。 */
internal fun buildAppList(
    installedApps: List<AppInfo>,
    targetPackages: Set<String>,
    searchQuery: String,
    includeMissingSelected: Boolean,
    sortMode: AppSortMode = AppSortMode.APP_NAME,
    sortDescending: Boolean = false,
): List<AppInfo> {
    val installedPackages = installedApps.asSequence().map { it.packageName }.toSet()
    val missingSelectedApps = if (includeMissingSelected) {
        (targetPackages - installedPackages).map { packageName ->
            AppInfo(
                packageName = packageName,
                appName = packageName,
                isInstalled = false,
            )
        }
    } else {
        emptyList()
    }
    val allApps = installedApps + missingSelectedApps
    val query = searchQuery.trim()
    val filtered = if (query.isEmpty()) {
        allApps
    } else {
        allApps.filter {
            it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    val ascendingComparator = when (sortMode) {
        AppSortMode.APP_NAME ->
            compareBy<AppInfo> { it.appName.lowercase() }
                .thenBy { it.packageName.lowercase() }
        AppSortMode.PACKAGE_NAME ->
            compareBy<AppInfo> { it.packageName.lowercase() }
                .thenBy { it.appName.lowercase() }
        AppSortMode.INSTALL_TIME ->
            compareBy<AppInfo> { it.firstInstallTime }
                .thenBy { it.appName.lowercase() }
                .thenBy { it.packageName.lowercase() }
        AppSortMode.UPDATE_TIME ->
            compareBy<AppInfo> { it.lastUpdateTime }
                .thenBy { it.appName.lowercase() }
                .thenBy { it.packageName.lowercase() }
    }
    val fieldComparator = if (sortDescending) {
        ascendingComparator.reversed()
    } else {
        ascendingComparator
    }

    return filtered.sortedWith(
        compareByDescending<AppInfo> { it.packageName in targetPackages }
            .then(fieldComparator),
    )
}
