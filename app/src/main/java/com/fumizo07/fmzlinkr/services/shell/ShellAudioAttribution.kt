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
 * Temporarily detach only mInitialApplication while an AudioRecord is being constructed. This
 * dedicated FMZlinkR UserService process then follows the same shell attribution path as a plain
 * app_process command. The original Application is restored in finally before returning.
 *
 * This never modifies the Shizuku server, adbd, debugging settings, or another app's UserService.
 */
internal object ShellAudioAttribution {
    private val lock = Any()

    fun <T> withoutClientApplication(operation: String, block: () -> T): T = synchronized(lock) {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass
            .getMethod("currentActivityThread")
            .invoke(null)
            ?: throw IllegalStateException("ActivityThread is unavailable in Shizuku UserService")
        val initialApplicationField = activityThreadClass
            .getDeclaredField("mInitialApplication")
            .apply { isAccessible = true }
        val originalApplication = initialApplicationField.get(activityThread)
        val originalPackage = runCatching {
            originalApplication?.javaClass?.getMethod("getPackageName")?.invoke(originalApplication) as? String
        }.getOrNull()

        initialApplicationField.set(activityThread, null)
        try {
            val shellPackage = currentAttributionPackage()
            AppLogger.d(
                "FMZlinkR $operation shell audio attribution: " +
                    "detachedPackage=$originalPackage resolvedPackage=$shellPackage uid=${Process.myUid()}",
            )
            block()
        } finally {
            initialApplicationField.set(activityThread, originalApplication)
        }
    }

    private fun currentAttributionPackage(): String? = runCatching {
        val sourceClass = Class.forName("android.content.AttributionSource")
        val source = sourceClass.getMethod("myAttributionSource").invoke(null) ?: return@runCatching null
        sourceClass.getMethod("getPackageName").invoke(source) as? String
    }.getOrNull()
}
