package com.rconegliam.bumpmap.sync

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class RecordingSyncTest {
    @Test
    fun generatesValid64LowercaseHexEnrollmentToken() {
        val fixed = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                for (i in bytes.indices) bytes[i] = i.toByte()
            }
        }
        val token = generateEnrollmentToken(fixed)
        assertEquals(64, token.length)
        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", token)
        assertTrue(isValidEnrollmentToken(token))
        assertFalse(isValidEnrollmentToken(token.uppercase()))
        assertFalse(isValidEnrollmentToken("short"))
    }

    @Test
    fun persistsEnrollmentTokenDurablyAndNeverOverwritesExistingToken() {
        val prefs = MemoryPreferences()
        val first = RecordingSync.enrollmentToken(prefs)
        assertTrue(isValidEnrollmentToken(first))
        assertEquals(1, prefs.commits)
        assertEquals(first, RecordingSync.enrollmentToken(prefs))
        assertEquals(1, prefs.commits)
    }

    @Test
    fun clearLegacyTokenPreservesInstallationIdAndEnrollmentToken() {
        val prefs = MemoryPreferences(
            mutableMapOf(
                RecordingSync.LEGACY_TOKEN to "old-user-token",
                RecordingSync.INSTALLATION_ID to "existing-install-id",
                RecordingSync.ENROLLMENT_TOKEN to "a".repeat(64),
                "mobile-data" to true,
            ),
        )

        RecordingSync.clearLegacyToken(prefs)

        assertFalse(prefs.contains(RecordingSync.LEGACY_TOKEN))
        assertEquals("existing-install-id", RecordingSync.installationId(prefs))
        assertEquals("a".repeat(64), RecordingSync.enrollmentToken(prefs))
        assertEquals(true, prefs.getBoolean("mobile-data", false))
    }

    private class MemoryPreferences(
        private val data: MutableMap<String, Any?> = mutableMapOf(),
    ) : SharedPreferences {
        var commits = 0

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in data
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val updates = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?) = apply { updates[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { updates[key] = values }
            override fun putInt(key: String, value: Int) = apply { updates[key] = value }
            override fun putLong(key: String, value: Long) = apply { updates[key] = value }
            override fun putFloat(key: String, value: Float) = apply { updates[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { updates[key] = value }
            override fun remove(key: String) = apply { removals += key }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                commits++
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach(data::remove)
                data.putAll(updates)
            }
        }
    }
}
