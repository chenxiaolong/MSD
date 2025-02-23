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
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.chiller3.msd.R
import com.chiller3.msd.databinding.ImageSizeDialogBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import java.util.regex.Pattern

class ImageSizeDialogFragment : DialogFragment() {
    companion object {
        val TAG: String = ImageSizeDialogFragment::class.java.simpleName

        private const val ARG_URI = "uri"
        private const val ARG_EXISTING_SIZE = "existing_size"
        const val RESULT_ACTION = "action"

        private val REGEX_SIZE =
            Pattern.compile("^(\\d+)\\s*((?:[GMK]i?)?B)?$", Pattern.CASE_INSENSITIVE)

        fun newForCreate() = ImageSizeDialogFragment().apply {
            arguments = bundleOf()
        }

        fun newForResize(uri: Uri, existingSize: Long) = ImageSizeDialogFragment().apply {
            arguments = bundleOf(
                ARG_URI to uri,
                ARG_EXISTING_SIZE to existingSize,
            )
        }
    }

    @Parcelize
    sealed interface Action : Parcelable

    @Parcelize
    data class CreateImage(val size: Long) : Action

    @Parcelize
    data class ResizeImage(val uri: Uri, val size: Long) : Action

    private val uri by lazy {
        BundleCompat.getParcelable(requireArguments(), ARG_URI, Uri::class.java)
    }
    private lateinit var binding: ImageSizeDialogBinding
    private var size: Long? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // We shouldn't be able to open the dialog if the size of the image is unknown, but if that
        // somehow happens anyway, make (nearly) every value the user enters show a warning.
        val arguments = requireArguments()
        require((uri != null) == arguments.containsKey(ARG_EXISTING_SIZE))
        val existingSize = arguments.getLong(ARG_EXISTING_SIZE, 0)

        binding = ImageSizeDialogBinding.inflate(layoutInflater)

        binding.message.text = buildString {
            if (uri == null) {
                append(getString(R.string.dialog_image_size_create_message))
            } else {
                append(getString(R.string.dialog_image_size_resize_message, existingSize))
            }

            append("\n\n")
            append(getString(R.string.dialog_image_size_units_message))
        }

        binding.text.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        binding.text.addTextChangedListener {
            size = null
            binding.textLayout.helperText = null

            try {
                val matcher = REGEX_SIZE.matcher(it.toString())
                if (!matcher.matches()) {
                    return@addTextChangedListener
                }

                val number = try {
                    matcher.group(1)!!.toLong()
                } catch (_: NumberFormatException) {
                    return@addTextChangedListener
                }
                val multiplier = when (val suffix = matcher.group(2)?.lowercase()) {
                    "b", null -> 1 shl 0
                    "kib" -> 1 shl 10
                    "mib" -> 1 shl 20
                    "gib" -> 1 shl 30
                    "kb" -> 1000
                    "mb" -> 1000 * 1000
                    "gb" -> 1000 * 1000 * 1000
                    else -> throw IllegalStateException("Bad suffix: $suffix")
                }

                if (number == 0L || number > Long.MAX_VALUE / multiplier) {
                    return@addTextChangedListener
                }

                val newSize = number * multiplier

                if (newSize < existingSize) {
                    binding.textLayout.error =
                        getString(R.string.dialog_image_size_truncate_warning)
                } else {
                    binding.textLayout.error = null
                    // Don't keep the layout space for the error message reserved.
                    binding.textLayout.isErrorEnabled = false
                }

                size = newSize
                binding.textLayout.suffixText =
                    if (binding.textLayout.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                        "$size B ="
                    } else {
                        "= $size B"
                    }
            } finally {
                refreshOkButtonEnabledState()
            }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_image_size_title)
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                size = null
            }
            .create()
            .apply {
                setCanceledOnTouchOutside(false)
            }
    }

    override fun onStart() {
        super.onStart()
        refreshOkButtonEnabledState()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)

        val action = size?.let { size ->
            uri?.let { ResizeImage(it, size) } ?: CreateImage(size)
        }

        setFragmentResult(tag!!, bundleOf(RESULT_ACTION to action))
    }

    private fun refreshOkButtonEnabledState() {
        (dialog as AlertDialog?)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = size != null
    }
}
