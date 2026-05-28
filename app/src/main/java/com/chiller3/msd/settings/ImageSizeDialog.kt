/*
 * SPDX-FileCopyrightText: 2024-2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.settings

import android.net.Uri
import android.os.Parcelable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.chiller3.msd.R
import kotlinx.parcelize.Parcelize
import java.util.regex.Pattern

@Parcelize
sealed interface ImageSizeAction : Parcelable {
    @Parcelize
    data object Create : ImageSizeAction

    @Parcelize
    data class Resize(val uri: Uri, val existingSize: Long) : ImageSizeAction
}

@Parcelize
sealed interface ImageSizeResult : Parcelable {
    @Parcelize
    data class Create(val size: Long) : ImageSizeResult

    @Parcelize
    data class Resize(val uri: Uri, val size: Long) : ImageSizeResult
}

@Composable
fun ImageSizeDialog(
    action: ImageSizeAction,
    onSelect: (ImageSizeResult) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val parsed = tryParseInput(input, action)

    AlertDialog(
        title = { Text(text = stringResource(R.string.dialog_image_size_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(state = rememberScrollState())) {
                Text(text = buildMessage(action))

                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    value = input,
                    onValueChange = { input = it },
                    suffix = {
                        if (parsed is ImageSizeParse.Value) {
                            Text(text = parsed.suffix)
                        }
                    },
                    isError = parsed is ImageSizeParse.Error,
                    supportingText = {
                        if (parsed is ImageSizeParse.Error && parsed.message != null) {
                            Text(text = parsed.message)
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val size = (parsed as ImageSizeParse.Value).size
                    val result = when (action) {
                        ImageSizeAction.Create -> ImageSizeResult.Create(size)
                        is ImageSizeAction.Resize -> ImageSizeResult.Resize(action.uri, size)
                    }

                    onSelect(result)
                },
                enabled = parsed is ImageSizeParse.Value,
            ) {
                Text(text = stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(android.R.string.cancel))
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    )
}

@Composable
private fun buildMessage(action: ImageSizeAction) = buildString {
    when (action) {
        ImageSizeAction.Create -> append(stringResource(R.string.dialog_image_size_create_message))
        is ImageSizeAction.Resize ->
            append(stringResource(R.string.dialog_image_size_resize_message, action.existingSize))
    }

    append("\n\n")
    append(stringResource(R.string.dialog_image_size_units_message))
}

private sealed interface ImageSizeParse {
    data class Value(val size: Long, val suffix: String) : ImageSizeParse

    data class Error(val message: String?) : ImageSizeParse
}

private val REGEX_SIZE = Pattern.compile("^(\\d+)\\s*((?:[GMK]i?)?B)?$", Pattern.CASE_INSENSITIVE)

@Composable
private fun tryParseInput(input: String, action: ImageSizeAction): ImageSizeParse {
    val matcher = REGEX_SIZE.matcher(input)
    if (!matcher.matches()) {
        return ImageSizeParse.Error(null)
    }

    val number = try {
        matcher.group(1)!!.toLong()
    } catch (_: NumberFormatException) {
        return ImageSizeParse.Error(null)
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
        return ImageSizeParse.Error(null)
    }

    val size = number * multiplier

    if (action is ImageSizeAction.Resize && size < action.existingSize) {
        return ImageSizeParse.Error(stringResource(R.string.dialog_image_size_truncate_warning))
    }

    val suffix = when (LocalLayoutDirection.current) {
        LayoutDirection.Ltr -> "= $size B"
        LayoutDirection.Rtl -> "$size B ="
    }

    return ImageSizeParse.Value(size, suffix)
}
