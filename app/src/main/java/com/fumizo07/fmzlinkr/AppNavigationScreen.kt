/*
 * FMZlinkR
 * Derived from ShizuCallRecorder.
 * Copyright (C) 2026-present kitsumed (Med)
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.fumizo07.fmzlinkr.data.FmzPreferences
import com.fumizo07.fmzlinkr.ui.screens.FmzDisclaimerScreen
import com.fumizo07.fmzlinkr.ui.screens.FmzSettingsScreen
import com.fumizo07.fmzlinkr.ui.theme.FMZlinkRTheme

@Composable
fun AppNavigationScreen() {
    val context = LocalContext.current
    val prefs = remember { FmzPreferences(context) }
    var accepted by remember { mutableStateOf(prefs.isDisclaimerAccepted()) }

    FMZlinkRTheme(
        darkTheme = isSystemInDarkTheme(),
        dynamicColor = true,
    ) {
        if (!accepted) {
            FmzDisclaimerScreen {
                prefs.setDisclaimerAccepted(true)
                accepted = true
            }
        } else {
            FmzSettingsScreen()
        }
    }
}
