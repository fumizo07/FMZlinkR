/*
 * FMZlinkR shell UserService.
 * Derived from ShizuCallRecorder and CallVault VoIP capture work.
 * Copyright (C) 2026-present kitsumed (Med)
 * Copyright (C) 2026-present The CallVault Authors
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.services.shell

import android.content.Context
import android.os.Binder
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import com.fumizo07.fmzlinkr.ILogCallback
import com.fumizo07.fmzlinkr.IShellService
import com.fumizo07.fmzlinkr.utils.AppLogger
import kotlin.system.exitProcess

/** Runs only as FMZlinkR's Shizuku UserService. It never manages the Shizuku server itself. */
@Keep
class ShellService : IShellService.Stub {
    @Volatile private var voipSession: VoipCaptureSession? = null
    @Volatile private var lastVoipFarPartyHeard = false
    @Volatile private var lastVoipNearPartyHeard = false

    @Keep constructor() : this(null)

    @Keep
    constructor(context: Context?) {
        val processPackage = currentProcessPackageName()
        AppLogger.i(
            "FMZlinkR ShellService started uid=${android.os.Process.myUid()} " +
                "context=${context != null} contextPackage=${context?.packageName} processPackage=$processPackage",
        )
    }

    override fun setLogCallback(listener: ILogCallback, isRedactionEnabled: Boolean) {
        AppLogger.initAsRemote(listener, isRedactionEnabled)
        AppLogger.i(
            "FMZlinkR ShellService logger attached uid=${android.os.Process.myUid()} " +
                "processPackage=${currentProcessPackageName()}",
        )
    }

    override fun armVoipCapture(): Boolean = withShellCallingIdentity("armVoipCapture") {
        VoipAudioPolicy.arm()
    }

    override fun disarmVoipCapture() = withShellCallingIdentity("disarmVoipCapture") {
        stopVoipRecordingInternal()
        VoipAudioPolicy.disarm()
    }

    @Synchronized
    override fun startVoipRecording(audioBitRate: Int, outFd: ParcelFileDescriptor): Boolean =
        withShellCallingIdentity("startVoipRecording") {
            AppLogger.i(
                "VoIP start requested uid=${android.os.Process.myUid()} callingUid=${Binder.getCallingUid()} " +
                    "armed=${VoipAudioPolicy.isArmed} active=${voipSession != null}",
            )
            if (voipSession != null) {
                AppLogger.w("VoIP start rejected: session already active")
                runCatching { outFd.close() }
                return@withShellCallingIdentity false
            }
            if (!VoipAudioPolicy.isArmed) {
                AppLogger.w("VoIP start rejected: AudioPolicy is not armed")
                runCatching { outFd.close() }
                return@withShellCallingIdentity false
            }
            val bitRate = audioBitRate.coerceAtLeast(8_000)
            val session = VoipCaptureSession(bitRate, outFd)
            runCatching {
                session.start()
                lastVoipFarPartyHeard = false
                lastVoipNearPartyHeard = false
                voipSession = session
                AppLogger.i("VoIP dual capture session started successfully")
                true
            }.onFailure {
                AppLogger.e("VoIP dual capture could not start: ${it.message}", it)
                runCatching { outFd.close() }
            }.getOrDefault(false)
        }

    @Synchronized
    override fun stopVoipRecording() = withShellCallingIdentity("stopVoipRecording") {
        stopVoipRecordingInternal()
    }

    private fun stopVoipRecordingInternal() {
        val session = voipSession ?: return
        voipSession = null
        runCatching { session.stop() }
            .onFailure { AppLogger.e("VoIP dual capture stop failed: ${it.message}", it) }
        lastVoipFarPartyHeard = session.farPartyHeard
        lastVoipNearPartyHeard = session.nearPartyHeard
    }

    override fun voipFarPartyHeard(): Boolean = voipSession?.farPartyHeard ?: lastVoipFarPartyHeard
    override fun voipNearPartyHeard(): Boolean = voipSession?.nearPartyHeard ?: lastVoipNearPartyHeard
    override fun voipCallAppUid(): Int = withShellCallingIdentity("voipCallAppUid") {
        VoipAppIdentity.currentVoiceCommUid()
    }

    /** Special Shizuku destroy transaction. Exits only this app's UserService process. */
    override fun destroy() {
        val token = Binder.clearCallingIdentity()
        try {
            AppLogger.i("FMZlinkR ShellService destroy")
            stopVoipRecordingInternal()
            VoipAudioPolicy.disarm()
            exitProcess(0)
        } finally {
            // Normally unreachable because this process exits, but keep Binder identity handling paired.
            Binder.restoreCallingIdentity(token)
        }
    }

    /**
     * AIDL methods execute while handling an incoming Binder transaction from the ordinary app UID.
     * Android audio policy performs some permission checks against Binder.getCallingUid(), so privileged
     * audio work must temporarily use this UserService process' own shell uid (2000). The caller identity
     * is always restored before returning to the app.
     */
    private inline fun <T> withShellCallingIdentity(operation: String, block: () -> T): T {
        val incomingUid = Binder.getCallingUid()
        val token = Binder.clearCallingIdentity()
        return try {
            AppLogger.d(
                "FMZlinkR $operation Binder identity: incomingUid=$incomingUid " +
                    "effectiveUid=${Binder.getCallingUid()} processUid=${android.os.Process.myUid()}",
            )
            block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun currentProcessPackageName(): String? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentPackageName")
            .invoke(null) as String?
    }.getOrNull()
}
