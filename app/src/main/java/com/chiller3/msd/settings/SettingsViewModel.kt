/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.net.toFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chiller3.msd.Preferences
import com.chiller3.msd.daemon.Client
import com.chiller3.msd.daemon.ClientException
import com.chiller3.msd.extension.toSingleLineString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface Alert {
    data class QueryStateFailure(val error: String) : Alert

    data class ApplyStateFailure(val error: String) : Alert

    data object ReapplyRequired : Alert

    data object NotLocalFile : Alert

    data class CreateImageFailure(val error: String) : Alert
}

private val Throwable.alertMessage: String
    get() = if (this is ClientException) {
        message!!
    } else {
        toSingleLineString()
    }

data class UiDeviceInfo(
    val uri: Uri,
    val type: DeviceType,
    val enabled: Boolean,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val TAG = SettingsViewModel::class.java.simpleName

        @WorkerThread
        private fun getLocalPath(pfd: ParcelFileDescriptor): String =
            Os.readlink("/proc/self/fd/${pfd.fd}")

        // Only local files will ever work. Even if a SAF provider provides a regular file via a
        // FUSE mount with StorageManager.openProxyFileDescriptor(), it won't work because Android's
        // FuseAppLoop implementation disallows reopening files. The kernel has no interface for
        // accepting an already-opened file descriptor.
        // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/com/android/internal/os/FuseAppLoop.java;l=269;drc=5d123b67756dffcfdebdb936ab2de2b29c799321
        private fun rejectAppFuse(path: String) {
            if (path.startsWith("/mnt/appfuse/")) {
                throw IOException("StorageManager proxied fd is not reopenable")
            }
        }

        @WorkerThread
        private fun ensureRegularFile(pfd: ParcelFileDescriptor) {
            val stat = Os.fstat(pfd.fileDescriptor)
            if (stat.st_mode and OsConstants.S_IFMT != OsConstants.S_IFREG) {
                throw IOException("Not a regular file")
            }
        }
    }

    private val context: Context
        get() = getApplication()
    private val prefs = Preferences(context)

    private var operationsInProgress = 0

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts

    private val _canAct = MutableStateFlow(false)
    val canAct: StateFlow<Boolean> = _canAct

    private val _devices = MutableStateFlow<List<UiDeviceInfo>>(emptyList())
    val devices: StateFlow<List<UiDeviceInfo>> = _devices

    private val _activeFunctions = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeFunctions: StateFlow<Map<String, String>> = _activeFunctions

    init {
        refreshUsbState()
    }

    private fun refreshUiLock() {
        _canAct.update { operationsInProgress == 0 }
    }

    @WorkerThread
    private fun openFd(uri: Uri, mode: String): ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, mode)
            ?: throw IOException("File provider recently crashed for $uri")

    private suspend fun <T> withLockedUi(block: suspend () -> T): T {
        operationsInProgress += 1
        refreshUiLock()

        try {
            return block()
        } finally {
            operationsInProgress -= 1
            refreshUiLock()
        }
    }

    fun refreshUsbState() {
        viewModelScope.launch {
            withLockedUi {
                var functions = emptyMap<String, String>()
                var activeDevices = emptyList<DeviceInfo>()

                try {
                    withContext(Dispatchers.IO) {
                        Client().use {
                            functions = it.getFunctions()
                            activeDevices = it.getMassStorage()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to query USB state", e)
                    _alerts.update { it + Alert.QueryStateFailure(e.alertMessage) }
                }

                val devices = mutableListOf<UiDeviceInfo>()

                for (device in prefs.devices) {
                    val path = try {
                        withContext(Dispatchers.IO) {
                            openFd(device.uri, "r").use {
                                getLocalPath(it)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to query path of ${device.uri}", e)
                        // Ignore this. The backing file likely got deleted. The user will get an
                        // error message when they try to use this file.
                        ""
                    }

                    // Given how low the kernel/hardware limit is for the number of mass storage
                    // devices, a dumb linear search is fast enough.
                    val activeDevice = activeDevices.find { it.uri.toFile().toString() == path }
                    val deviceType = activeDevice?.type ?: device.type

                    devices.add(UiDeviceInfo(device.uri, deviceType, activeDevice != null))
                }

                _activeFunctions.update { functions }
                _devices.update { devices }
            }
        }
    }

    private fun writePrefs() {
        prefs.devices = devices.value.map {
            DeviceInfo(it.uri, it.type)
        }
    }

    fun addDevice(uri: Uri, deviceType: DeviceType) {
        viewModelScope.launch {
            withLockedUi {
                val newDevices = ArrayList(devices.value)
                val index = newDevices.indexOfFirst { it.uri == uri }

                if (index >= 0) {
                    newDevices[index] = newDevices[index].copy(type = deviceType)
                } else {
                    try {
                        withContext(Dispatchers.IO) {
                            openFd(uri, "r").use {
                                rejectAppFuse(getLocalPath(it))
                                ensureRegularFile(it)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to check image: $uri", e)
                        _alerts.update { it + Alert.NotLocalFile }
                        return@withLockedUi
                    }

                    newDevices.add(UiDeviceInfo(uri, deviceType, true))

                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                _devices.update { newDevices }

                // Add to persistent settings as well.
                writePrefs()

                if (Alert.ReapplyRequired !in _alerts.value) {
                    _alerts.update { it + Alert.ReapplyRequired }
                }
            }
        }
    }

    fun createDevice(uri: Uri, size: Long) {
        viewModelScope.launch {
            withLockedUi {
                try {
                    withContext(Dispatchers.IO) {
                        openFd(uri, "rwt").use {
                            Os.ftruncate(it.fileDescriptor, size)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create image: $uri", e)
                    _alerts.update { it + Alert.CreateImageFailure(e.toSingleLineString()) }
                    return@withLockedUi
                }

                addDevice(uri, DeviceType.DISK_RW)
            }
        }
    }

    fun removeDevice(uri: Uri) {
        // It's not documented, but this can throw an exception when trying to release a previously
        // persisted URI that's associated with an app that's no longer installed.
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error when releasing persisted URI permission for: $uri", e)
        }

        _devices.update { devices -> devices.filter { it.uri != uri } }

        // Remove from persistent settings as well.
        writePrefs()
    }

    fun toggleDevice(uri: Uri, enabled: Boolean) {
        _devices.update { devices ->
            devices.map {
                if (it.uri == uri) {
                    it.copy(enabled = enabled)
                } else {
                    it
                }
            }
        }

        // No persistent settings to modify.
    }

    fun setMassStorage() {
        viewModelScope.launch {
            withLockedUi {
                try {
                    withContext(Dispatchers.IO) {
                        Client().use {
                            it.setMassStorage(context, devices.value.mapNotNull { device ->
                                if (device.enabled) {
                                    DeviceInfo(device.uri, device.type)
                                } else {
                                    null
                                }
                            })
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set mass storage devices", e)
                    _alerts.update { it + Alert.ApplyStateFailure(e.alertMessage) }
                }

                refreshUsbState()
            }
        }
    }

    fun acknowledgeFirstAlert() {
        _alerts.update { it.drop(1) }
    }
}
