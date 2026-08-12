/*
 * FMZlinkR VoIP identity resolver.
 * Based on CallVault VoIP app identity work.
 * Copyright (C) 2026-present The CallVault Authors
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.services.shell

import com.fumizo07.fmzlinkr.utils.AppLogger
import java.util.concurrent.TimeUnit

internal object VoipAppIdentity {
    const val UID_UNKNOWN = -1
    private const val USAGE_VOICE_COMMUNICATION = 2
    private const val APP_UID_START = 10_000
    private const val DUMP_TIMEOUT_MS = 1_500L
    private const val RESOLVE_BUDGET_MS = 1_200L
    private const val RETRY_GAP_MS = 150L
    private const val SHELL = "/system/bin/sh"

    fun currentVoiceCommUid(): Int {
        val deadline = System.currentTimeMillis() + RESOLVE_BUDGET_MS
        do {
            val uid = resolveOnce()
            if (uid != UID_UNKNOWN) return uid
            if (System.currentTimeMillis() >= deadline) break
            runCatching { Thread.sleep(RETRY_GAP_MS) }.onFailure { break }
        } while (true)
        return UID_UNKNOWN
    }

    private fun resolveOnce(): Int {
        val viaBinder = runCatching { uidFromPlaybackConfigurations() }
            .onFailure { AppLogger.d("VoIP playback-config uid lookup unavailable: ${it.message}") }
            .getOrDefault(UID_UNKNOWN)
        if (viaBinder != UID_UNKNOWN) return viaBinder
        val dump = runCatching { readAudioDump() }.getOrNull().orEmpty()
        if (dump.isEmpty()) return UID_UNKNOWN
        val modeOwner = parseModeOwnerUid(dump)
        return if (modeOwner != UID_UNKNOWN) modeOwner else parseVoiceCommUid(dump)
    }

    private fun uidFromPlaybackConfigurations(): Int {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java).invoke(null, "audio")
            ?: return UID_UNKNOWN
        val stub = Class.forName("android.media.IAudioService\$Stub")
        val audioService = stub.getMethod("asInterface", Class.forName("android.os.IBinder"))
            .invoke(null, binder) ?: return UID_UNKNOWN
        val configurations = audioService.javaClass.getMethod("getActivePlaybackConfigurations")
            .invoke(audioService) as? List<*> ?: return UID_UNKNOWN
        for (configuration in configurations.filterNotNull()) {
            val attributes = runCatching {
                configuration.javaClass.getMethod("getAudioAttributes").invoke(configuration)
            }.getOrNull() ?: continue
            val usage = runCatching {
                attributes.javaClass.getMethod("getUsage").invoke(attributes) as? Int
            }.getOrNull() ?: continue
            if (usage != USAGE_VOICE_COMMUNICATION) continue
            val active = runCatching {
                configuration.javaClass.getMethod("isActive").invoke(configuration) as? Boolean ?: true
            }.getOrDefault(true)
            if (!active) continue
            val uid = runCatching {
                configuration.javaClass.getMethod("getClientUid").invoke(configuration) as? Int
            }.getOrNull() ?: continue
            if (uid >= APP_UID_START) return uid
        }
        return UID_UNKNOWN
    }

    internal fun parseVoiceCommUid(dump: String): Int {
        for (line in dump.lineSequence()) {
            if (!line.contains("AudioPlaybackConfiguration")) continue
            if (!line.contains("usage=USAGE_VOICE_COMMUNICATION")) continue
            if (!line.contains("state:started")) continue
            val uid = UID_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (uid >= APP_UID_START) return uid
        }
        return UID_UNKNOWN
    }

    internal fun parseModeOwnerUid(dump: String): Int {
        for (line in dump.lineSequence()) {
            if (!line.contains("mAudioModeOwner")) continue
            if (!line.contains("mMode=MODE_IN_COMMUNICATION")) continue
            val uid = MODE_OWNER_UID_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (uid >= APP_UID_START) return uid
        }
        return UID_UNKNOWN
    }

    private fun readAudioDump(): String? {
        val process = ProcessBuilder(SHELL, "-c", "dumpsys audio")
            .redirectErrorStream(true)
            .start()
        return try {
            if (!process.waitFor(DUMP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return null
            }
            process.inputStream.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
        } finally {
            runCatching { process.destroy() }
        }
    }

    private val UID_REGEX = Regex("""u/pid:(\d+)/\d+""")
    private val MODE_OWNER_UID_REGEX = Regex("""mUid=(\d+)""")
}
