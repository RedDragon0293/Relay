package cn.reddragon.relay.util

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceHelperTest {
    @Test
    fun defaultsAndValidServerConfigArePersisted() {
        val prefs = MemorySharedPreferences()
        val helper = PreferenceHelper(prefs)

        assertEquals(PreferenceHelper.DEFAULT_SERVER_HOST, helper.getServerHost())
        assertEquals(PreferenceHelper.DEFAULT_SERVER_PORT, helper.getNotificationPort())

        assertTrue(helper.setServerConfig(" 10.0.0.8 ", 9000))
        assertEquals("10.0.0.8", helper.getServerHost())
        assertEquals(9000, helper.getNotificationPort())
    }

    @Test
    fun invalidServerConfigDoesNotReplaceLastValidValue() {
        val helper = PreferenceHelper(MemorySharedPreferences())
        assertTrue(helper.setServerConfig("10.0.0.9", 9001))

        assertFalse(helper.setServerConfig(" ", 9002))
        assertFalse(helper.setServerConfig("10.0.0.10", 65536))
        assertEquals("10.0.0.9", helper.getServerHost())
        assertEquals(9001, helper.getNotificationPort())
    }

    @Test
    fun targetPackagesAreReturnedAsIndependentSnapshots() {
        val prefs = MemorySharedPreferences()
        val helper = PreferenceHelper(prefs)
        helper.setTargetPackages(setOf("one.app", "two.app"))

        val first = helper.getTargetPackages()
        val second = helper.getTargetPackages()

        assertEquals(setOf("one.app", "two.app"), first)
        assertEquals(first, second)
        assertNotSame(first, second)
    }
}

private class MemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()
    private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val updates = linkedMapOf<String, Any?>()
        private val removals = linkedSetOf<String>()
        private var clearRequested = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            updates[key] = values?.toSet()
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }
        override fun clear(): SharedPreferences.Editor = apply { clearRequested = true }
        override fun commit(): Boolean {
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun applyChanges() {
            val changed = linkedSetOf<String>()
            if (clearRequested) {
                changed += values.keys
                values.clear()
            }
            removals.forEach {
                if (values.remove(it) != null) changed += it
            }
            updates.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
                changed += key
            }
            changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this@MemorySharedPreferences, key) } }
        }
    }
}
