/*
 * SPDX-FileCopyrightText: 2024-2025 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.chiller3.msd.R
import com.chiller3.msd.settings.DeviceType
import com.chiller3.msd.settings.UiDeviceInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize

open class DeviceDialogFragment : DialogFragment() {
    companion object {
        val TAG: String = DeviceDialogFragment::class.java.simpleName

        private const val ARG_DEVICE = "device"
        const val RESULT_ACTION = "action"

        fun newInstance(device: UiDeviceInfo?): DeviceDialogFragment =
            DeviceDialogFragment().apply {
                arguments = bundleOf(
                    ARG_DEVICE to device,
                )
            }
    }

    @Parcelize
    sealed interface Action : Parcelable

    @Parcelize
    data class AddDevice(val deviceType: DeviceType) : Action

    @Parcelize
    data class ChangeDevice(val uri: Uri, val deviceType: DeviceType) : Action

    @Parcelize
    data object CreateDevice : Action

    @Parcelize
    data class ResizeDevice(val uri: Uri, val existingSize: Long) : Action

    @Parcelize
    data class RemoveDevice(val uri: Uri) : Action

    private val device by lazy {
        BundleCompat.getParcelable(requireArguments(), ARG_DEVICE, UiDeviceInfo::class.java)
    }
    private val items by lazy {
        mutableListOf<Pair<Action, String>>().apply {
            val device = device

            if (device != null) {
                if (device.type != DeviceType.CDROM) {
                    add(ChangeDevice(device.uri, DeviceType.CDROM) to
                            getString(R.string.dialog_device_switch_cdrom))
                }
                if (device.type != DeviceType.DISK_RO) {
                    add(ChangeDevice(device.uri, DeviceType.DISK_RO) to
                            getString(R.string.dialog_device_switch_disk_ro))
                }
                if (device.type != DeviceType.DISK_RW) {
                    add(ChangeDevice(device.uri, DeviceType.DISK_RW) to
                            getString(R.string.dialog_device_switch_disk_rw))
                } else if (device.size != null) {
                    add(ResizeDevice(device.uri, device.size) to
                            getString(R.string.dialog_device_resize_disk_rw))
                }
                add(RemoveDevice(device.uri) to getString(R.string.dialog_device_delete))
            } else {
                add(AddDevice(DeviceType.CDROM) to getString(R.string.dialog_device_add_cdrom))
                add(AddDevice(DeviceType.DISK_RO) to getString(R.string.dialog_device_add_disk_ro))
                add(AddDevice(DeviceType.DISK_RW) to getString(R.string.dialog_device_add_disk_rw))
                add(CreateDevice to getString(R.string.dialog_device_create_disk_rw))
            }
        }
    }

    private var action: Action? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val title = if (device != null) {
            getString(R.string.dialog_device_title_edit)
        } else {
            getString(R.string.dialog_device_title_add)
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setItems(items.map { it.second }.toTypedArray()) { _, i ->
                action = items[i].first
                dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                dismiss()
            }
            .create()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        setFragmentResult(tag!!, bundleOf(RESULT_ACTION to action))
    }
}
