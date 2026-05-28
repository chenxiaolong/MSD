/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.chiller3.msd.settings.DeviceInfo

class Preferences(context: Context) {
    companion object {
        // Keep in the same order as the helper functions below.
        private const val PREF_DEVICE_PREFIX = "device_"
        private const val PREF_DEBUG_MODE = "debug_mode"
    }

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var devices: List<DeviceInfo>
        get() {
            val devices = mutableListOf<DeviceInfo>()
            while (true) {
                val prefix = "${PREF_DEVICE_PREFIX}${devices.size}_"
                val rule = DeviceInfo.fromRawPreferences(prefs, prefix) ?: break
                devices.add(rule)
            }
            return devices
        }
        set(devices) = prefs.edit {
            val keys = prefs.all.keys.filter { it.startsWith(PREF_DEVICE_PREFIX) }
            for (key in keys) {
                remove(key)
            }

            for ((i, rule) in devices.withIndex()) {
                rule.toRawPreferences(this, "${PREF_DEVICE_PREFIX}${i}_")
            }
        }

    var isDebugMode: Boolean
        get() = prefs.getBoolean(PREF_DEBUG_MODE, false)
        set(enabled) = prefs.edit { putBoolean(PREF_DEBUG_MODE, enabled) }

    /** Remove persistent per-device enable state from version <= 1.3. */
    fun migrationRemoveEnableState() = prefs.edit {
        val keys = prefs.all.keys.filter {
            it.startsWith(PREF_DEVICE_PREFIX) && it.endsWith("_enabled")
        }
        for (key in keys) {
            remove(key)
        }
    }
}
