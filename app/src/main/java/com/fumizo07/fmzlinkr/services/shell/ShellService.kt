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

    @Keep constructor() : this(null)

    @Keep
    constructor(context: Context?) {
        AppLogger.i("FMZlinkR ShellService started uid=${android.os.Process.myUid()} context=${context != null}")
    }

    override fun setLogCallback(listener: ILogCallback, isRedactionEnabled: Boolean) {
        AppLogger.initAsRemote(listener, isRedactionEnabled)
    }

    override fun armVoipCapture(): Boolean = VoipAudioPolicy.arm()

    override fun disarmVoipCapture() {
        stopVoipRecording()
        VoipAudioPolicy.disarm()
    }

    @Synchronized
    override fun startVoipRecording(audioBitRate: Int, outFd: ParcelFileDescriptor): Boolean {
        if (voipSession != null) {
            AppLogger.w("VoIP start rejected: session already active")
            runCatching { outFd.close() }
            return false
        }
        if (!VoipAudioPolicy.isArmed) {
            AppLogger.w("VoIP start rejected: AudioPolicy is not armed")
            runCatching { outFd.close() }
            return false
        }
        val bitRate = audioBitRate.coerceAtLeast(8_000)
        val session = VoipCaptureSession(bitRate, outFd)
        return runCatching {
            session.start()
            lastVoipFarPartyHeard = false
            voipSession = session
            true
        }.onFailure {
            AppLogger.e("VoIP dual capture could not start: ${it.message}", it)
            runCatching { outFd.close() }
        }.getOrDefault(false)
    }

    @Synchronized
    override fun stopVoipRecording() {
        val session = voipSession ?: return
        voipSession = null
        runCatching { session.stop() }
            .onFailure { AppLogger.e("VoIP dual capture stop failed: ${it.message}", it) }
        lastVoipFarPartyHeard = session.farPartyHeard
    }

    override fun voipFarPartyHeard(): Boolean = voipSession?.farPartyHeard ?: lastVoipFarPartyHeard
    override fun voipCallAppUid(): Int = VoipAppIdentity.currentVoiceCommUid()

    /** Special Shizuku destroy transaction. Exits only this app's UserService process. */
    override fun destroy() {
        AppLogger.i("FMZlinkR ShellService destroy")
        stopVoipRecording()
        VoipAudioPolicy.disarm()
        exitProcess(0)
    }
}
