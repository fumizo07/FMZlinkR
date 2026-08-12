/*
 * FMZlinkR theme, derived from ShizuCallRecorder.
 * Copyright (C) 2026-present kitsumed (Med)
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Green80,
    onPrimary = DeepDarkGreen,
    primaryContainer = GreenContainerDark,
    onPrimaryContainer = GreenContainerLight,
    secondary = GreenGrey80,
    onSecondary = DarkGreyGreen,
    tertiary = AccentGreen80,
    onTertiary = AccentGreenDark,
    surface = DarkSurface,
    onSurface = OffWhiteText,
    outline = GreyGreenOutline,
)

private val LightColorScheme = lightColorScheme(
    primary = Green40,
    onPrimary = White,
    primaryContainer = GreenContainerLight,
    onPrimaryContainer = VeryDarkForest,
    secondary = GreenGrey40,
    onSecondary = White,
    surface = LightSurface,
    onSurface = NearBlackText,
    outline = GreyGreenOutline,
)

@Composable
fun FMZlinkRTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
