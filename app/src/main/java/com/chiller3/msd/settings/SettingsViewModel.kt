/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.Log
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
    data class GetFunctionsFailure(val error: String) : Alert

    data class SetMassStorageFailure(val error: String) : Alert

    data object ReenableRequired : Alert

    data object NotLocalFile : Alert

    data class CreateImageFailure(val error: String) : Alert
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private val TAG = SettingsViewModel::class.java.simpleName

        private const val CONFIG = "msd"
    }

    private val context: Context
        get() = getApplication()
    private val prefs = Preferences(context)

    private var operationsInProgress = 0

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts

    private val _canRefresh = MutableStateFlow(false)
    val canRefresh: StateFlow<Boolean> = _canRefresh

    private val _canEnable = MutableStateFlow(false)
    val canEnable: StateFlow<Boolean> = _canEnable

    private val _canDisable = MutableStateFlow(false)
    val canDisable: StateFlow<Boolean> = _canDisable

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices

    private val _activeFunctions = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeFunctions: StateFlow<Map<String, String>> = _activeFunctions

    init {
        refreshFunctions()
        refreshDevices()
    }

    private fun refreshUiLocks() {
        val ok = operationsInProgress == 0

        _canRefresh.update { ok }
        _canEnable.update { ok && _devices.value.isNotEmpty() }
        _canDisable.update { ok && _activeFunctions.value.contains(CONFIG) }
    }

    private suspend fun <T> withLockedUi(block: suspend () -> T): T {
        operationsInProgress += 1
        refreshUiLocks()

        try {
            return block()
        } finally {
            operationsInProgress -= 1
            refreshUiLocks()
        }
    }

    fun refreshFunctions() {
        viewModelScope.launch {
            withLockedUi {
                val functions = try {
                    withContext(Dispatchers.IO) {
                        Client().use {
                            it.getFunctions()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to list functions", e)
                    val message = if (e is ClientException) {
                        e.message!!
                    } else {
                        e.toSingleLineString()
                    }
                    _alerts.update { it + Alert.GetFunctionsFailure(message) }
                    emptyMap()
                }

                _activeFunctions.update { functions }
            }
        }
    }

    private fun refreshDevices() {
        _devices.update { prefs.devices }
        refreshUiLocks()
    }

    fun addDevice(uri: Uri, deviceType: DeviceType) {
        viewModelScope.launch {
            withLockedUi {
                val newDevices = ArrayList(prefs.devices)
                val index = newDevices.indexOfFirst { it.uri == uri }

                if (index >= 0) {
                    newDevices[index] = DeviceInfo(uri, deviceType)
                } else {
                    newDevices.add(DeviceInfo(uri, deviceType))

                    // Only local files will ever work. Even if a SAF provider provides a regular
                    // file via a FUSE mount with StorageManager.openProxyFileDescriptor(), it won't
                    // work because Android's FuseAppLoop implementation disallows reopening files.
                    // The kernel has no interface for accepting an already-opened file descriptor.
                    // https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/com/android/internal/os/FuseAppLoop.java;l=269;drc=5d123b67756dffcfdebdb936ab2de2b29c799321
                    try {
                        withContext(Dispatchers.IO) {
                            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                                ?: throw IOException("File provider recently crashed for $uri")
                            fd.use {
                                val stat = Os.fstat(fd.fileDescriptor)
                                if (stat.st_mode and OsConstants.S_IFMT != OsConstants.S_IFREG) {
                                    throw IOException("Not a regular file")
                                }

                                val target = Os.readlink("/proc/self/fd/${fd.fd}")
                                if (target.startsWith("/mnt/appfuse/")) {
                                    throw IOException("StorageManager proxied fd is not reopenable")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to check image: $uri", e)
                        _alerts.update { it + Alert.NotLocalFile }
                        return@withLockedUi
                    }

                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                prefs.devices = newDevices
                refreshDevices()

                if (Alert.ReenableRequired !in _alerts.value) {
                    _alerts.update { it + Alert.ReenableRequired }
                }
            }
        }
    }

    fun createDevice(uri: Uri, size: Long) {
        viewModelScope.launch {
            withLockedUi {
                try {
                    withContext(Dispatchers.IO) {
                        val fd = context.contentResolver.openFileDescriptor(uri, "rwt")
                            ?: throw IOException("File provider recently crashed for $uri")
                        fd.use {
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

        prefs.devices = devices.value.filter { it.uri != uri }
        refreshDevices()
    }

    private fun setMassStorage(newDevices: List<DeviceInfo>) {
        viewModelScope.launch {
            withLockedUi {
                try {
                    withContext(Dispatchers.IO) {
                        Client().use {
                            it.setMassStorage(context, newDevices)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set mass storage devices", e)
                    val message = if (e is ClientException) {
                        e.message!!
                    } else {
                        e.toSingleLineString()
                    }
                    _alerts.update { it + Alert.SetMassStorageFailure(message) }
                }

                refreshFunctions()
            }
        }
    }

    fun enableMassStorage() {
        setMassStorage(prefs.devices)
    }

    fun disableMassStorage() {
        setMassStorage(emptyList())
    }

    fun acknowledgeFirstAlert() {
        _alerts.update { it.drop(1) }
    }
}
