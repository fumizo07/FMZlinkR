/*
 * FMZlinkR shell audio attribution helper.
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.services.shell

import android.os.Process
import com.fumizo07.fmzlinkr.utils.AppLogger

/**
 * Shizuku UserService deliberately installs the client application's Application into
 * ActivityThread.mInitialApplication even though the process itself runs as shell uid 2000.
 *
 * AudioRecord resolves AttributionSource through ActivityThread.currentAttributionSource() before
 * falling back to the process uid. That makes a shell process claim FMZlinkR's package while native
 * AudioRecord validates the uid/package pair, leaving the record STATE_UNINITIALIZED.
 *
 * FMZlinkR's UserService is a dedicated process and does not use the client Application after the
 * service binder has been created. Detaching mInitialApplication therefore restores the command-line
 * shell attribution model used by the proven CallVault app_process recorder while keeping the same
 * Shizuku UserService process and lifecycle.
 *
 * This never modifies the Shizuku server, adbd, debugging settings, or another app's UserService.
 */
internal object ShellAudioAttribution {
    private val lock = Any()

    /**
     * Detach FMZlinkR's Application from this dedicated UserService process. Idempotent.
     * The already-created service binder/classloader remain alive; only framework attribution lookup
     * stops treating uid 2000 as the FMZlinkR package.
     */
    fun detachClientApplicationPermanently() = synchronized(lock) {
        val state = activityThreadState()
        val originalApplication = state.initialApplicationField.get(state.activityThread)
        val originalPackage = packageNameOf(originalApplication)
        if (originalApplication != null) {
            state.initialApplicationField.set(state.activityThread, null)
        }
        AppLogger.i(
            "FMZlinkR UserService application attribution detached: " +
                "previousPackage=$originalPackage resolvedPackage=${currentAttributionPackage()} uid=${Process.myUid()}",
        )
    }

    /**
     * Additional scoped guard for AudioRecord construction. It is normally a no-op after the permanent
     * detach, but remains paired with finally so the audio path is safe if Shizuku changes startup
     * behavior or another framework component restores mInitialApplication later.
     */
    fun <T> withoutClientApplication(operation: String, block: () -> T): T = synchronized(lock) {
        val state = activityThreadState()
        val originalApplication = state.initialApplicationField.get(state.activityThread)
        val originalPackage = packageNameOf(originalApplication)

        state.initialApplicationField.set(state.activityThread, null)
        try {
            AppLogger.d(
                "FMZlinkR $operation shell audio attribution: " +
                    "detachedPackage=$originalPackage resolvedPackage=${currentAttributionPackage()} uid=${Process.myUid()}",
            )
            block()
        } finally {
            // If the service was already permanently detached, originalApplication is null and remains so.
            state.initialApplicationField.set(state.activityThread, originalApplication)
        }
    }

    private fun activityThreadState(): ActivityThreadState {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass
            .getMethod("currentActivityThread")
            .invoke(null)
            ?: throw IllegalStateException("ActivityThread is unavailable in Shizuku UserService")
        val initialApplicationField = activityThreadClass
            .getDeclaredField("mInitialApplication")
            .apply { isAccessible = true }
        return ActivityThreadState(activityThread, initialApplicationField)
    }

    private fun packageNameOf(application: Any?): String? = runCatching {
        application?.javaClass?.getMethod("getPackageName")?.invoke(application) as? String
    }.getOrNull()

    private fun currentAttributionPackage(): String? = runCatching {
        val sourceClass = Class.forName("android.content.AttributionSource")
        val source = sourceClass.getMethod("myAttributionSource").invoke(null) ?: return@runCatching null
        sourceClass.getMethod("getPackageName").invoke(source) as? String
    }.getOrNull()

    private data class ActivityThreadState(
        val activityThread: Any,
        val initialApplicationField: java.lang.reflect.Field,
    )
}
