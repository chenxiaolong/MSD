/*
 * SPDX-FileCopyrightText: Google
 * SPDX-License-Identifier: Apache-2.0
 *
 * All icons here originated from Material Symbols: https://fonts.google.com/icons
 */

package com.chiller3.msd.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object Icons {
    object AutoMirrored {
        // https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/chevron_right.kt?var=opsz,wght,FILL,GRAD,ROND@24,300,0,0,50
        val ChevronRight: ImageVector
            get() {
                if (_ChevronRight != null) {
                    return _ChevronRight!!
                }
                _ChevronRight =
                    ImageVector.Builder(
                        name = "chevron_right",
                        defaultWidth = 24.dp,
                        defaultHeight = 24.dp,
                        viewportWidth = 24f,
                        viewportHeight = 24f,
                        autoMirror = true,
                    )
                        .apply {
                            path(
                                fill = SolidColor(Color.Black),
                                fillAlpha = 1f,
                                stroke = null,
                                strokeAlpha = 1f,
                                strokeLineWidth = 1f,
                                strokeLineCap = StrokeCap.Butt,
                                strokeLineJoin = StrokeJoin.Bevel,
                                strokeLineMiter = 1f,
                                pathFillType = PathFillType.NonZero,
                            ) {
                                moveTo(12.95f, 12f)
                                lineTo(8.35f, 7.4f)
                                lineTo(9.4f, 6.35f)
                                lineTo(15.05f, 12f)
                                lineTo(9.4f, 17.65f)
                                lineTo(8.35f, 16.6f)
                                lineTo(12.95f, 12f)
                                close()
                            }
                        }
                        .build()
                return _ChevronRight!!
            }

        private var _ChevronRight: ImageVector? = null
    }

    // https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/check.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
    val Check: ImageVector
        get() {
            if (_Check != null) {
                return _Check!!
            }
            _Check =
                ImageVector.Builder(
                    name = "check",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(9.55f, 18f)
                            lineTo(3.85f, 12.3f)
                            lineTo(5.28f, 10.88f)
                            lineToRelative(4.28f, 4.28f)
                            lineTo(18.73f, 5.97f)
                            lineTo(20.15f, 7.4f)
                            lineTo(9.55f, 18f)
                            close()
                        }
                    }
                    .build()
            return _Check!!
        }

    private var _Check: ImageVector? = null

    // https://fonts.gstatic.com/render/v1/Material+Symbols+Outlined/24dp/close.kt?var=opsz,wght,FILL,GRAD,ROND@24,400,0,0,50
    val Close: ImageVector
        get() {
            if (_Close != null) {
                return _Close!!
            }
            _Close =
                ImageVector.Builder(
                    name = "close",
                    defaultWidth = 24.dp,
                    defaultHeight = 24.dp,
                    viewportWidth = 24f,
                    viewportHeight = 24f,
                )
                    .apply {
                        path(
                            fill = SolidColor(Color.Black),
                            fillAlpha = 1f,
                            stroke = null,
                            strokeAlpha = 1f,
                            strokeLineWidth = 1f,
                            strokeLineCap = StrokeCap.Butt,
                            strokeLineJoin = StrokeJoin.Bevel,
                            strokeLineMiter = 1f,
                            pathFillType = PathFillType.NonZero,
                        ) {
                            moveTo(6.4f, 19f)
                            lineTo(5f, 17.6f)
                            lineTo(10.6f, 12f)
                            lineTo(5f, 6.4f)
                            lineTo(6.4f, 5f)
                            lineTo(12f, 10.6f)
                            lineTo(17.6f, 5f)
                            lineTo(19f, 6.4f)
                            lineTo(13.4f, 12f)
                            lineTo(19f, 17.6f)
                            lineTo(17.6f, 19f)
                            lineTo(12f, 13.4f)
                            lineTo(6.4f, 19f)
                            close()
                        }
                    }
                    .build()
            return _Close!!
        }

    private var _Close: ImageVector? = null
}
