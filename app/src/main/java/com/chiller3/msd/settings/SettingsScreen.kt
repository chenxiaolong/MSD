/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chiller3.msd.BuildConfig
import com.chiller3.msd.Preferences
import com.chiller3.msd.R
import com.chiller3.msd.extension.DOCUMENTSUI_AUTHORITY
import com.chiller3.msd.extension.formattedString
import com.chiller3.msd.ui.AppScreen
import com.chiller3.msd.ui.BetterSegmentedShapes
import com.chiller3.msd.ui.Preference
import com.chiller3.msd.ui.PreferenceCategory
import com.chiller3.msd.ui.PreferenceColumn
import com.chiller3.msd.ui.SplitSwitchPreference
import com.chiller3.msd.ui.betterSegmentedShapes
import com.chiller3.msd.ui.theme.AppTheme
import kotlinx.parcelize.Parcelize

@Parcelize
private sealed interface PartialState : Parcelable {
    @Parcelize
    data class ExistingImageTypeSelected(val deviceType: DeviceType) : PartialState

    @Parcelize
    data class NewImageSizeSelected(val size: Long) : PartialState
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val resources = LocalResources.current

    val prefs = remember { Preferences(context) }
    var reloadPrefs by remember { mutableIntStateOf(0) }
    val isDebugMode = remember(reloadPrefs) { prefs.isDebugMode }

    val canAct by viewModel.canAct.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val activeFunctions by viewModel.activeFunctions.collectAsStateWithLifecycle()

    var partialState by rememberSaveable { mutableStateOf<PartialState?>(null) }

    val requestSafOpenImage =
        rememberLauncherForActivityResult(OpenPersistentDocument()) { uri ->
            val state = partialState as PartialState.ExistingImageTypeSelected
            partialState = null

            uri?.let {
                viewModel.addDevice(it, state.deviceType)
            }
        }
    val requestSafCreateImage =
        rememberLauncherForActivityResult(CreatePersistentDocument("*/*")) { uri ->
            val state = partialState as PartialState.NewImageSizeSelected
            partialState = null

            uri?.let {
                viewModel.createDevice(it, state.size, SettingsViewModel.OpenMode.CREATE)
            }
        }

    var showErrorDialog by rememberSaveable { mutableStateOf<String?>(null) }

    AppScreen(
        title = { Text(text = stringResource(R.string.app_name)) },
    ) { params ->
        LaunchedEffect(Unit) {
            viewModel.alerts.collect { alerts ->
                val alert = alerts.firstOrNull() ?: return@collect
                val msg = when (alert) {
                    is Alert.QueryStateFailure ->
                        resources.getString(R.string.alert_query_state_failure)
                    is Alert.ApplyStateFailure ->
                        resources.getString(R.string.alert_apply_state_failure)
                    is Alert.ReapplyRequired ->
                        resources.getString(R.string.alert_reapply_required)
                    is Alert.NotLocalFile ->
                        resources.getString(R.string.alert_not_local_file)
                    is Alert.CreateImageFailure ->
                        resources.getString(R.string.alert_create_image_failure)
                    is Alert.ResizeImageFailure ->
                        resources.getString(R.string.alert_resize_image_failure)
                    Alert.BrowserNotFound ->
                        resources.getString(R.string.alert_browser_not_found)
                    Alert.DocumentsUINotFound ->
                        resources.getString(R.string.alert_documentsui_not_found)
                }
                val details = when (alert) {
                    is Alert.QueryStateFailure -> alert.error
                    is Alert.ApplyStateFailure -> alert.error
                    is Alert.ReapplyRequired -> null
                    is Alert.NotLocalFile ->
                        resources.getString(R.string.alert_not_local_file_details)
                    is Alert.CreateImageFailure -> alert.error
                    is Alert.ResizeImageFailure -> alert.error
                    Alert.BrowserNotFound -> null
                    Alert.DocumentsUINotFound -> null
                }

                val result = params.snackbarHostState.showSnackbar(
                    message = msg,
                    details?.let { resources.getString(R.string.action_details) },
                    withDismissAction = true,
                )
                viewModel.acknowledgeFirstAlert()

                when (result) {
                    SnackbarResult.Dismissed -> {}
                    SnackbarResult.ActionPerformed -> { showErrorDialog = details }
                }
            }
        }

        showErrorDialog?.let { message ->
            ErrorDetailsDialog(
                title = null,
                message = message,
                onDismiss = { showErrorDialog = null },
            )
        }

        SettingsContent(
            canAct = canAct,
            devices = devices,
            activeFunctions = activeFunctions,
            isDebugMode = isDebugMode,
            onDeviceAdd = { deviceType ->
                partialState = PartialState.ExistingImageTypeSelected(deviceType)

                // AOSP does not have any MIME types for .iso or .img files.
                // See: frameworks/base/mime/java-res/android.mime.types
                requestSafOpenImage.launch(arrayOf("*/*"))
            },
            onDeviceChange = { uri, deviceType ->
                viewModel.addDevice(uri, deviceType)
            },
            onDeviceRemove = { uri ->
                viewModel.removeDevice(uri)
            },
            onDeviceToggle = { device, enabled ->
                viewModel.toggleDevice(device.uri, enabled)
            },
            onImageCreate = { size ->
                partialState = PartialState.NewImageSizeSelected(size)
                requestSafCreateImage.launch("disk.img")
            },
            onImageResize = { uri, size ->
                viewModel.createDevice(uri, size, SettingsViewModel.OpenMode.RESIZE)
            },
            onActiveFunctions = {
                viewModel.refreshUsbState()
            },
            onApplySettings = {
                viewModel.setMassStorage()
            },
            onDebugModeChange = { enabled ->
                prefs.isDebugMode = enabled
                reloadPrefs++
            },
            onSourceRepoOpen = {
                val uri = BuildConfig.PROJECT_URL_AT_COMMIT.toUri()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    viewModel.addAlert(Alert.BrowserNotFound)
                }
            },
            onOpenLogDir = {
                val externalDir = Environment.getExternalStorageDirectory()
                val filesDir = context.getExternalFilesDir(null)!!
                val relPath = filesDir.relativeTo(externalDir)
                val uri = DocumentsContract.buildDocumentUri(
                    DOCUMENTSUI_AUTHORITY, "primary:$relPath")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "vnd.android.document/directory")
                }

                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    viewModel.addAlert(Alert.DocumentsUINotFound)
                }
            },
            contentPadding = params.contentPadding,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsContent(
    canAct: Boolean,
    devices: List<UiDeviceInfo>,
    activeFunctions: Map<String, String>,
    isDebugMode: Boolean,
    onDeviceAdd: (DeviceType) -> Unit,
    onDeviceChange: (Uri, DeviceType) -> Unit,
    onDeviceRemove: (Uri) -> Unit,
    onDeviceToggle: (UiDeviceInfo, Boolean) -> Unit,
    onImageCreate: (Long) -> Unit,
    onImageResize: (Uri, Long) -> Unit,
    onActiveFunctions: () -> Unit,
    onApplySettings: () -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onSourceRepoOpen: () -> Unit,
    onOpenLogDir: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var showDeviceDialog by rememberSaveable { mutableStateOf<DeviceDialogType?>(null) }
    var showImageSizeDialog by rememberSaveable { mutableStateOf<ImageSizeAction?>(null) }

    PreferenceColumn(contentPadding = contentPadding) {
        item(key = "devices") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_devices)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "add_device") {
            Preference(
                onClick = { showDeviceDialog = DeviceDialogType.New },
                enabled = canAct,
                shapes = if (devices.isEmpty()) {
                    BetterSegmentedShapes.single()
                } else {
                    BetterSegmentedShapes.top()
                },
                title = { Text(text = stringResource(R.string.pref_add_device_name)) },
                summary = { Text(text = stringResource(R.string.pref_add_device_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        itemsIndexed(devices, key = { _, device -> device.uri }) { index, device ->
            SplitSwitchPreference(
                onClick = { showDeviceDialog = DeviceDialogType.Existing(device) },
                checked = device.enabled,
                onCheckedChange = { onDeviceToggle(device, it) },
                enabled = canAct,
                shapes = betterSegmentedShapes(index = index + 1, count = devices.size + 1),
                title = { Text(text = deviceTitle(device)) },
                summary = { Text(text = device.localPath ?: device.uri.formattedString) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "usb") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_usb)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "active_functions") {
            Preference(
                onClick = onActiveFunctions,
                shapes = BetterSegmentedShapes.top(),
                title = { Text(text = stringResource(R.string.pref_active_functions_name)) },
                summary = { Text(text = activeFunctionsSummary(activeFunctions)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "apply_settings") {
            Preference(
                onClick = onApplySettings,
                shapes = BetterSegmentedShapes.bottom(),
                title = { Text(text = stringResource(R.string.pref_enable_mass_storage_name)) },
                summary = { Text(text = stringResource(R.string.pref_enable_mass_storage_desc)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "about") {
            PreferenceCategory(
                title = { Text(text = stringResource(R.string.pref_header_about)) },
                modifier = Modifier.animateItem(),
            )
        }

        item(key = "version") {
            Preference(
                onClick = onSourceRepoOpen,
                onLongClick = { onDebugModeChange(!isDebugMode) },
                shapes = BetterSegmentedShapes.single(),
                title = { Text(text = stringResource(R.string.pref_version_name)) },
                summary = { Text(text = versionSummary(isDebugMode)) },
                modifier = Modifier.animateItem(),
            )
        }

        if (isDebugMode) {
            item(key = "debug") {
                PreferenceCategory(
                    title = { Text(text = stringResource(R.string.pref_header_debug)) },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "open_log_dir") {
                Preference(
                    onClick = onOpenLogDir,
                    shapes = BetterSegmentedShapes.single(),
                    title = { Text(text = stringResource(R.string.pref_open_log_dir_name)) },
                    summary = { Text(text = stringResource(R.string.pref_open_log_dir_desc)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    showDeviceDialog?.let { type ->
        DeviceDialog(
            type = type,
            onSelect = { action ->
                when (action) {
                    is DeviceAction.Add -> {
                        onDeviceAdd(action.deviceType)
                    }
                    is DeviceAction.Change -> {
                        onDeviceChange(action.uri, action.deviceType)
                    }
                    DeviceAction.Create -> {
                        showImageSizeDialog = ImageSizeAction.Create
                    }
                    is DeviceAction.Resize -> {
                        showImageSizeDialog = ImageSizeAction.Resize(action.uri, action.existingSize)
                    }
                    is DeviceAction.Remove -> {
                        onDeviceRemove(action.uri)
                    }
                }

                @Suppress("AssignedValueIsNeverRead")
                showDeviceDialog = null
            },
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showDeviceDialog = null
            },
        )
    }

    showImageSizeDialog?.let { action ->
        ImageSizeDialog(
            action = action,
            onSelect = { result ->
                when (result) {
                    is ImageSizeResult.Create -> onImageCreate(result.size)
                    is ImageSizeResult.Resize -> onImageResize(result.uri, result.size)
                }

                @Suppress("AssignedValueIsNeverRead")
                showImageSizeDialog = null
            },
            onDismiss = {
                @Suppress("AssignedValueIsNeverRead")
                showImageSizeDialog = null
            },
        )
    }
}

@Composable
private fun deviceTitle(device: UiDeviceInfo) = when (device.type) {
    DeviceType.CDROM -> stringResource(R.string.pref_device_name_cdrom)
    DeviceType.DISK_RO -> stringResource(R.string.pref_device_name_disk_ro)
    DeviceType.DISK_RW -> stringResource(R.string.pref_device_name_disk_rw)
}

@Composable
private fun activeFunctionsSummary(functions: Map<String, String>) = if (functions.isEmpty()) {
    stringResource(R.string.pref_active_functions_desc_none)
} else {
    buildString {
        var first = true

        for ((config, function) in functions) {
            if (first) {
                first = false
            } else {
                append('\n')
            }
            append(config)
            append(": ")
            append(function)
        }
    }
}

@Composable
private fun versionSummary(isDebugMode: Boolean): String {
    val suffix = if (isDebugMode) "+debugmode" else ""

    return "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}${suffix})"
}

@SuppressLint("SdCardPath")
@Preview(
    name = "Light Mode",
    showBackground = true,
)
@Preview(
    name = "Dark Mode",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
private fun PreviewSettingsScreen() {
    val devices = listOf(
        UiDeviceInfo(
            uri = DocumentsContract.buildDocumentUri(DOCUMENTSUI_AUTHORITY, "primary:cdrom.img"),
            localPath = "/sdcard/cdrom.img",
            type = DeviceType.CDROM,
            enabled = true,
            size = 700_000_000,
        ),
        UiDeviceInfo(
            uri = DocumentsContract.buildDocumentUri(DOCUMENTSUI_AUTHORITY, "primary:disk_ro.img"),
            localPath = "/sdcard/disk_ro.img",
            type = DeviceType.DISK_RO,
            enabled = false,
            size = 1024 * 1024 * 1024,
        ),
        UiDeviceInfo(
            uri = DocumentsContract.buildDocumentUri(DOCUMENTSUI_AUTHORITY, "primary:disk_rw.img"),
            localPath = null,
            type = DeviceType.DISK_RW,
            enabled = false,
            size = 64 * 1024 * 1024,
        )
    )
    val activeFunctions = mapOf(
        "function0" to "ffs.adb",
        "msd" to "mass_storage.msd",
    )

    AppTheme {
        AppScreen(
            title = { Text(text = stringResource(R.string.app_name)) },
        ) { params ->
            SettingsContent(
                canAct = true,
                devices = devices,
                activeFunctions = activeFunctions,
                isDebugMode = true,
                onDeviceAdd = {},
                onDeviceChange = { _, _ -> },
                onDeviceRemove = {},
                onDeviceToggle = { _, _ -> },
                onImageCreate = {},
                onImageResize = { _, _ -> },
                onActiveFunctions = {},
                onApplySettings = {},
                onDebugModeChange = {},
                onSourceRepoOpen = {},
                onOpenLogDir = {},
                contentPadding = params.contentPadding,
            )
        }
    }
}
