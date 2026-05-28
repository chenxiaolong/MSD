/*
 * SPDX-FileCopyrightText: 2026 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.chiller3.msd.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp

data class AppScreenParams(
    val contentPadding: PaddingValues,
    val snackbarHostState: SnackbarHostState,
)

@Composable
fun AppScreen(
    title: @Composable () -> Unit,
    content: @Composable (AppScreenParams) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = title,
                colors = PreferenceDefaults.appBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = PreferenceDefaults.containerColor,
    ) { contentPadding ->
        val outerPadding = contentPadding.copy(bottom = 0.dp)
        val innerPadding = contentPadding.copy(start = 0.dp, top = 0.dp, end = 0.dp)

        Box(modifier = Modifier.padding(outerPadding)) {
            content(AppScreenParams(
                contentPadding = innerPadding,
                snackbarHostState = snackbarHostState,
            ))
        }
    }
}
