/*
 * FMZlinkR lightweight logger.
 * Derived from ShizuCallRecorder's IPC logging concept.
 * Copyright (C) 2026-present kitsumed (Med)
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.utils

import android.content.Context
import android.util.Log
import com.fumizo07.fmzlinkr.ILogCallback

object AppLogger {
    private const val TAG = "FMZlinkR"
    @Volatile private var remoteCallback: ILogCallback? = null

    fun init(context: Context) {
        Log.i(TAG, "FMZlinkR logger initialized for ${context.packageName}")
    }

    fun initAsRemote(callback: ILogCallback, isRedactionEnabled: Boolean = true) {
        remoteCallback = callback
        Log.i(TAG, "FMZlinkR remote logger initialized, redaction=$isRedactionEnabled")
    }

    val callback: ILogCallback.Stub by lazy {
        object : ILogCallback.Stub() {
            override fun onLogEvent(level: String, tag: String, message: String, throwableStackTrace: String?) {
                val full = if (throwableStackTrace.isNullOrBlank()) message else "$message\n$throwableStackTrace"
                write(level, tag.ifBlank { TAG }, full, null, forward = false)
            }
        }
    }

    fun v(message: String, throwable: Throwable? = null) = write("V", TAG, message, throwable)
    fun d(message: String, throwable: Throwable? = null) = write("D", TAG, message, throwable)
    fun i(message: String, throwable: Throwable? = null) = write("I", TAG, message, throwable)
    fun w(message: String, throwable: Throwable? = null) = write("W", TAG, message, throwable)
    fun e(message: String, throwable: Throwable? = null) = write("E", TAG, message, throwable)

    private fun write(level: String, tag: String, message: String, throwable: Throwable?, forward: Boolean = true) {
        if (forward) {
            val callback = remoteCallback
            if (callback != null) {
                runCatching {
                    callback.onLogEvent(level, tag, message, throwable?.stackTraceToString())
                }.onSuccess { return }
            }
        }
        when (level) {
            "V" -> Log.v(tag, message, throwable)
            "D" -> Log.d(tag, message, throwable)
            "I" -> Log.i(tag, message, throwable)
            "W" -> Log.w(tag, message, throwable)
            else -> Log.e(tag, message, throwable)
        }
    }
}
