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

    /** Output codec/container. Supported values are "opus" (OGG) and "aac" (M4A). */
    fun getAudioCodec(): String = prefs.getString(KEY_AUDIO_CODEC, DEFAULT_AUDIO_CODEC)
        ?.takeIf { it == AUDIO_CODEC_OPUS || it == AUDIO_CODEC_AAC }
        ?: DEFAULT_AUDIO_CODEC
    fun setAudioCodec(value: String) = prefs.edit {
        putString(KEY_AUDIO_CODEC, if (value == AUDIO_CODEC_AAC) AUDIO_CODEC_AAC else AUDIO_CODEC_OPUS)
    }

    fun getAudioBitRate(): Int = prefs.getInt(KEY_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE)
        .takeIf { it in AUDIO_BITRATE_OPTIONS }
        ?: DEFAULT_AUDIO_BITRATE
    fun setAudioBitRate(value: Int) = prefs.edit {
        putInt(KEY_AUDIO_BITRATE, value.takeIf { it in AUDIO_BITRATE_OPTIONS } ?: DEFAULT_AUDIO_BITRATE)
    }

    /** Base filename only; the selected codec's extension is appended automatically. */
    fun getFileNameTemplate(): String = prefs.getString(KEY_FILE_NAME_TEMPLATE, DEFAULT_FILE_NAME_TEMPLATE)
        ?.takeIf { it.isNotBlank() }
        ?: DEFAULT_FILE_NAME_TEMPLATE
    fun setFileNameTemplate(value: String) = prefs.edit { putString(KEY_FILE_NAME_TEMPLATE, value) }

    companion object {
        const val AUDIO_CODEC_OPUS = "opus"
        const val AUDIO_CODEC_AAC = "aac"
        const val DEFAULT_AUDIO_BITRATE = 16_000
        const val DEFAULT_FILE_NAME_TEMPLATE = "{date}_{direction}_{app}"
        val AUDIO_BITRATE_OPTIONS = setOf(8_000, 16_000, 32_000, 64_000, 128_000)

        private const val DEFAULT_AUDIO_CODEC = AUDIO_CODEC_OPUS
        private const val PREFS_NAME = "fmzlinkr_prefs"
        private const val KEY_DISCLAIMER = "disclaimer_accepted"
        private const val KEY_FOLDER = "recording_folder_uri"
        private const val KEY_MONITORING = "rakuten_monitoring_enabled"
        private const val KEY_AUTO_RECORD = "rakuten_auto_record_enabled"
        private const val KEY_OVERLAY = "rakuten_overlay_enabled"
        private const val KEY_AUDIO_CODEC = "audio_codec"
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_FILE_NAME_TEMPLATE = "file_name_template"
    }
}
