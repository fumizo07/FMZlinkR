/*
 * FMZlinkR
 * Derived from ShizuCallRecorder.
 * Copyright (C) 2026-present kitsumed (Med)
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.fumizo07.fmzlinkr.data.FmzPreferences
import com.fumizo07.fmzlinkr.integrations.shizuku.ShizukuConnectionManager
import com.fumizo07.fmzlinkr.services.recording.RakutenLinkRecordingService

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppNavigationScreen() }
    }

    override fun onResume() {
        super.onResume()
        val prefs = FmzPreferences(applicationContext)
        if (
            prefs.isMonitoringEnabled() &&
            ShizukuConnectionManager.isAvailable() &&
            ShizukuConnectionManager.hasPermission(applicationContext)
        ) {
            RakutenLinkRecordingService.enable(applicationContext)
        }
    }
}
