/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalUnsignedTypes::class)

package com.chiller3.msd.settings

import android.net.Uri
import android.os.Parcelable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.chiller3.msd.R
import com.chiller3.msd.ui.Preference
import com.chiller3.msd.ui.PreferenceColumn
import com.chiller3.msd.ui.betterSegmentedShapes
import kotlinx.parcelize.Parcelize

@Parcelize
sealed interface DeviceDialogType : Parcelable {
    @Parcelize
    data object New : DeviceDialogType

    @Parcelize
    data class Existing(val device: UiDeviceInfo) : DeviceDialogType
}

@Parcelize
sealed interface DeviceAction : Parcelable {
    @Parcelize
    data class Add(val deviceType: DeviceType) : DeviceAction

    @Parcelize
    data class Change(val uri: Uri, val deviceType: DeviceType) : DeviceAction

    @Parcelize
    data object Create : DeviceAction

    @Parcelize
    data class Resize(val uri: Uri, val existingSize: Long) : DeviceAction

    @Parcelize
    data class Remove(val uri: Uri) : DeviceAction
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DeviceDialog(
    type: DeviceDialogType,
    onSelect: (DeviceAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = actionChoices(type)

    AlertDialog(
        title = { Text(text = actionTitle(type)) },
        text = {
            PreferenceColumn(fillScreen = false) {
                itemsIndexed(choices) { index, (result, text) ->
                    Preference(
                        onClick = { onSelect(result) },
                        shapes = betterSegmentedShapes(index = index, count = choices.size),
                        title = { Text(text = text) },
                    )
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun actionTitle(type: DeviceDialogType) = when (type) {
    DeviceDialogType.New -> stringResource(R.string.dialog_device_title_add)
    is DeviceDialogType.Existing -> stringResource(R.string.dialog_device_title_edit)
}

@Composable
private fun actionChoices(type: DeviceDialogType) =
    mutableListOf<Pair<DeviceAction, String>>().apply {
        when (type) {
            DeviceDialogType.New -> {
                add(DeviceAction.Add(DeviceType.CDROM) to
                        stringResource(R.string.dialog_device_add_cdrom))
                add(DeviceAction.Add(DeviceType.DISK_RO) to
                        stringResource(R.string.dialog_device_add_disk_ro))
                add(DeviceAction.Add(DeviceType.DISK_RW) to
                        stringResource(R.string.dialog_device_add_disk_rw))
                add(DeviceAction.Create to stringResource(R.string.dialog_device_create_disk_rw))
            }
            is DeviceDialogType.Existing -> {
                if (type.device.type != DeviceType.CDROM) {
                    add(DeviceAction.Change(type.device.uri, DeviceType.CDROM) to
                            stringResource(R.string.dialog_device_switch_cdrom))
                }
                if (type.device.type != DeviceType.DISK_RO) {
                    add(DeviceAction.Change(type.device.uri, DeviceType.DISK_RO) to
                            stringResource(R.string.dialog_device_switch_disk_ro))
                }
                if (type.device.type != DeviceType.DISK_RW) {
                    add(DeviceAction.Change(type.device.uri, DeviceType.DISK_RW) to
                            stringResource(R.string.dialog_device_switch_disk_rw))
                } else if (type.device.size != null) {
                    add(DeviceAction.Resize(type.device.uri, type.device.size) to
                            stringResource(R.string.dialog_device_resize_disk_rw))
                }
                add(DeviceAction.Remove(type.device.uri) to
                        stringResource(R.string.dialog_device_delete))
            }
        }
    }
