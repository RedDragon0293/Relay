package cn.reddragon.relay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListStateTest {
    @Test
    fun includesMissingSelectedPackageAsRemovableEntry() {
        val result = buildAppList(
            installedApps = listOf(AppInfo("installed.app", "Installed")),
            targetPackages = setOf("installed.app", "missing.app"),
            searchQuery = "",
            includeMissingSelected = true,
        )

        assertEquals(listOf("installed.app", "missing.app"), result.map { it.packageName })
        assertTrue(result.first { it.packageName == "installed.app" }.isInstalled)
        assertFalse(result.first { it.packageName == "missing.app" }.isInstalled)
    }

    @Test
    fun missingEntryDisappearsAfterItIsDeselected() {
        val result = buildAppList(
            installedApps = listOf(AppInfo("installed.app", "Installed")),
            targetPackages = emptySet(),
            searchQuery = "",
            includeMissingSelected = true,
        )

        assertEquals(listOf("installed.app"), result.map { it.packageName })
    }

    @Test
    fun trimsSearchAndKeepsSelectedAppsFirst() {
        val result = buildAppList(
            installedApps = listOf(
                AppInfo("alpha.app", "Alpha"),
                AppInfo("beta.app", "Beta"),
            ),
            targetPackages = setOf("beta.app"),
            searchQuery = " app ",
            includeMissingSelected = true,
        )

        assertEquals(listOf("beta.app", "alpha.app"), result.map { it.packageName })
    }

    @Test
    fun doesNotMarkTargetsMissingBeforeInitialScanFinishes() {
        val result = buildAppList(
            installedApps = emptyList(),
            targetPackages = setOf("pending.app"),
            searchQuery = "",
            includeMissingSelected = false,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun keepsSelectedFirstWhenSortingNamesDescending() {
        val result = buildAppList(
            installedApps = listOf(
                AppInfo("charlie.app", "Charlie"),
                AppInfo("alpha.app", "Alpha"),
                AppInfo("beta.app", "Beta"),
            ),
            targetPackages = setOf("alpha.app"),
            searchQuery = "",
            includeMissingSelected = true,
            sortMode = AppSortMode.APP_NAME,
            sortDescending = true,
        )

        assertEquals(
            listOf("alpha.app", "charlie.app", "beta.app"),
            result.map { it.packageName },
        )
    }

    @Test
    fun keepsSelectedFirstWhenSortingPackagesAscending() {
        val result = buildAppList(
            installedApps = listOf(
                AppInfo("z.app", "Alpha"),
                AppInfo("a.app", "Zulu"),
                AppInfo("m.app", "Middle"),
            ),
            targetPackages = setOf("z.app"),
            searchQuery = "",
            includeMissingSelected = true,
            sortMode = AppSortMode.PACKAGE_NAME,
        )

        assertEquals(listOf("z.app", "a.app", "m.app"), result.map { it.packageName })
    }

    @Test
    fun sortsByInstallTimeAscendingWithinSelectionGroups() {
        val result = buildAppList(
            installedApps = listOf(
                AppInfo("old.app", "Old", firstInstallTime = 100L),
                AppInfo("selected.app", "Selected", firstInstallTime = 300L),
                AppInfo("new.app", "New", firstInstallTime = 200L),
            ),
            targetPackages = setOf("selected.app"),
            searchQuery = "",
            includeMissingSelected = true,
            sortMode = AppSortMode.INSTALL_TIME,
        )

        assertEquals(
            listOf("selected.app", "old.app", "new.app"),
            result.map { it.packageName },
        )
    }

    @Test
    fun sortsByUpdateTimeDescendingWithinSelectionGroups() {
        val result = buildAppList(
            installedApps = listOf(
                AppInfo("selected.old", "Selected Old", lastUpdateTime = 100L),
                AppInfo("selected.new", "Selected New", lastUpdateTime = 300L),
                AppInfo("other.new", "Other New", lastUpdateTime = 500L),
                AppInfo("other.old", "Other Old", lastUpdateTime = 200L),
            ),
            targetPackages = setOf("selected.old", "selected.new"),
            searchQuery = "",
            includeMissingSelected = true,
            sortMode = AppSortMode.UPDATE_TIME,
            sortDescending = true,
        )

        assertEquals(
            listOf("selected.new", "selected.old", "other.new", "other.old"),
            result.map { it.packageName },
        )
    }
}
