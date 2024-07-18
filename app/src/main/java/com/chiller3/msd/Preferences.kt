/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.chiller3.msd.settings.DeviceInfo

class Preferences(context: Context) {
    companion object {
        const val CATEGORY_DEVICES = "devices"
        const val CATEGORY_DEBUG = "debug"

        const val PREF_ADD_DEVICE = "add_device"
        const val PREF_DEVICE_PREFIX = "device_"
        const val PREF_ACTIVE_FUNCTIONS = "active_functions"
        const val PREF_ENABLE_MASS_STORAGE = "enable_mass_storage"
        const val PREF_DISABLE_MASS_STORAGE = "disable_mass_storage"

        const val PREF_VERSION = "version"
        const val PREF_OPEN_LOG_DIR = "open_log_dir"

        // Not associated with a UI preference
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
}
