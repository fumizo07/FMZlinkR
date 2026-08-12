/*
 * FMZlinkR
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

class FmzPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDisclaimerAccepted(): Boolean = prefs.getBoolean(KEY_DISCLAIMER, false)
    fun setDisclaimerAccepted(value: Boolean) = prefs.edit { putBoolean(KEY_DISCLAIMER, value) }

    fun getRecordingFolderUri(): Uri? = prefs.getString(KEY_FOLDER, null)?.toUri()
    fun setRecordingFolderUri(uri: Uri?) = prefs.edit { putString(KEY_FOLDER, uri?.toString()) }

    /** Keeps the privileged AudioPolicy armed before the next Rakuten Link call is created. */
    fun isMonitoringEnabled(): Boolean = prefs.getBoolean(KEY_MONITORING, false)
    fun setMonitoringEnabled(value: Boolean) = prefs.edit { putBoolean(KEY_MONITORING, value) }

    /** Starts actual file recording automatically after Rakuten Link is positively identified. */
    fun isAutoRecordEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_RECORD, false)
    fun setAutoRecordEnabled(value: Boolean) = prefs.edit { putBoolean(KEY_AUTO_RECORD, value) }

    fun isOverlayEnabled(): Boolean = prefs.getBoolean(KEY_OVERLAY, true)
    fun setOverlayEnabled(value: Boolean) = prefs.edit { putBoolean(KEY_OVERLAY, value) }

    companion object {
        private const val PREFS_NAME = "fmzlinkr_prefs"
        private const val KEY_DISCLAIMER = "disclaimer_accepted"
        private const val KEY_FOLDER = "recording_folder_uri"
        private const val KEY_MONITORING = "rakuten_monitoring_enabled"
        private const val KEY_AUTO_RECORD = "rakuten_auto_record_enabled"
        private const val KEY_OVERLAY = "rakuten_overlay_enabled"
    }
}
