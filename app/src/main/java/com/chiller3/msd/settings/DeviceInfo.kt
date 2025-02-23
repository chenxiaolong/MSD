/*
 * SPDX-FileCopyrightText: 2024-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.settings

import android.content.SharedPreferences
import android.net.Uri
import android.os.Parcelable
import android.util.Log
import kotlinx.parcelize.Parcelize

@Parcelize
enum class DeviceType : Parcelable {
    CDROM,
    DISK_RO,
    DISK_RW,
}

@Parcelize
data class DeviceInfo(
    val uri: Uri,
    val type: DeviceType,
) : Parcelable {
    companion object {
        private val TAG = DeviceInfo::class.java.simpleName

        private const val PREF_SUFFIX_URI = "uri"
        private const val PREF_SUFFIX_TYPE = "type"

        fun fromRawPreferences(prefs: SharedPreferences, prefix: String): DeviceInfo? {
            val rawUri = prefs.getString(prefix + PREF_SUFFIX_URI, null) ?: return null
            val rawType = prefs.getString(prefix + PREF_SUFFIX_TYPE, null) ?: return null

            val uri = Uri.parse(rawUri)
            val type = try {
                DeviceType.valueOf(rawType)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Invalid device type: $rawType", e)
                return null
            }

            return DeviceInfo(uri, type)
        }
    }

    fun toRawPreferences(editor: SharedPreferences.Editor, prefix: String) {
        editor.putString(prefix + PREF_SUFFIX_URI, uri.toString())
        editor.putString(prefix + PREF_SUFFIX_TYPE, type.toString())
    }
}

@Parcelize
data class UiDeviceInfo(
    val uri: Uri,
    val localPath: String?,
    val type: DeviceType,
    val enabled: Boolean,
    val size: Long?,
) : Parcelable
