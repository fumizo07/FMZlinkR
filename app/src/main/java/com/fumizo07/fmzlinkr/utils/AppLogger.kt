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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

object AppLogger {
    private const val TAG = "FMZlinkR"
    private const val DIAGNOSTIC_MAX_LINES = 240
    private const val DIAGNOSTIC_MAX_LINE_CHARS = 1_800
    private val diagnosticTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val diagnosticLines = ArrayDeque<String>()

    @Volatile private var remoteCallback: ILogCallback? = null

    fun init(context: Context) {
        Log.i(TAG, "FMZlinkR logger initialized for ${context.packageName}")
        appendDiagnostic("I", TAG, "FMZlinkR app logger initialized")
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

    /**
     * Returns recent app + Shizuku UserService logs that were forwarded into the app process.
     * This intentionally lives only in memory: it is diagnostic state, not a recording or permanent log.
     */
    fun diagnosticSnapshot(maxLines: Int = 160): String = synchronized(diagnosticLines) {
        val limit = maxLines.coerceIn(1, DIAGNOSTIC_MAX_LINES)
        diagnosticLines.toList().takeLast(limit).joinToString("\n")
    }

    fun clearDiagnostics() = synchronized(diagnosticLines) {
        diagnosticLines.clear()
        appendDiagnosticLocked("I", TAG, "Diagnostic log cleared")
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?, forward: Boolean = true) {
        if (forward) {
            val callback = remoteCallback
            if (callback != null) {
                runCatching {
                    callback.onLogEvent(level, tag, message, throwable?.stackTraceToString())
                }.onSuccess { return }
            }
        }

        val localMessage = if (throwable == null) message else "$message\n${throwable.stackTraceToString()}"
        appendDiagnostic(level, tag, localMessage)

        when (level) {
            "V" -> Log.v(tag, message, throwable)
            "D" -> Log.d(tag, message, throwable)
            "I" -> Log.i(tag, message, throwable)
            "W" -> Log.w(tag, message, throwable)
            else -> Log.e(tag, message, throwable)
        }
    }

    private fun appendDiagnostic(level: String, tag: String, message: String) = synchronized(diagnosticLines) {
        appendDiagnosticLocked(level, tag, message)
    }

    private fun appendDiagnosticLocked(level: String, tag: String, message: String) {
        val compact = message
            .replace('\r', ' ')
            .replace('\n', '↩')
            .take(DIAGNOSTIC_MAX_LINE_CHARS)
        val time = runCatching { LocalTime.now().format(diagnosticTimeFormat) }.getOrDefault("--:--:--.---")
        diagnosticLines.addLast("$time $level/$tag $compact")
        while (diagnosticLines.size > DIAGNOSTIC_MAX_LINES) diagnosticLines.removeFirst()
    }
}
