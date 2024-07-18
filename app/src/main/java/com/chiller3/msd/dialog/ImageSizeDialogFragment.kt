/*
 * SPDX-FileCopyrightText: 2024 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.chiller3.msd.R
import com.chiller3.msd.databinding.ImageSizeDialogBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.regex.Pattern

class ImageSizeDialogFragment : DialogFragment() {
    companion object {
        val TAG: String = ImageSizeDialogFragment::class.java.simpleName

        const val RESULT_SIZE = "size"

        private val REGEX_SIZE =
            Pattern.compile("^(\\d+)\\s*((?:[GMK]i?)?B)?$", Pattern.CASE_INSENSITIVE)
    }

    private lateinit var binding: ImageSizeDialogBinding
    private var size: Long? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = ImageSizeDialogBinding.inflate(layoutInflater)

        binding.message.setText(R.string.dialog_image_size_message)

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
                } catch (e: NumberFormatException) {
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

                size = number * multiplier
                binding.textLayout.helperText = "$size B"
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

        setFragmentResult(tag!!, bundleOf(RESULT_SIZE to size))
    }

    private fun refreshOkButtonEnabledState() {
        (dialog as AlertDialog?)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = size != null
    }
}
