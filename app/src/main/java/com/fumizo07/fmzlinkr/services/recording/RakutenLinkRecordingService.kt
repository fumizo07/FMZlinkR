/*
 * FMZlinkR Rakuten Link recording service.
 * VoIP AudioPolicy/capture concepts based on CallVault.
 * Copyright (C) 2026-present The CallVault Authors
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.services.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.fumizo07.fmzlinkr.IShellService
import com.fumizo07.fmzlinkr.MainActivity
import com.fumizo07.fmzlinkr.R
import com.fumizo07.fmzlinkr.data.FmzPreferences
import com.fumizo07.fmzlinkr.integrations.shizuku.ShizukuConnectionManager
import com.fumizo07.fmzlinkr.system.storage.SafHelper
import com.fumizo07.fmzlinkr.utils.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the AudioPolicy armed before the next VoIP playback track is created. Actual file recording
 * is a separate decision, so a user may start/stop recording at any point during an eligible call.
 *
 * This service never starts, stops, or restarts Shizuku/adbd and never changes debugging settings.
 */
class RakutenLinkRecordingService : Service() {
    companion object {
        const val ACTION_ENABLE = "com.fumizo07.fmzlinkr.action.ENABLE_RAKUTEN_MONITORING"
        const val ACTION_DISABLE = "com.fumizo07.fmzlinkr.action.DISABLE_RAKUTEN_MONITORING"
        const val ACTION_MANUAL_START = "com.fumizo07.fmzlinkr.action.MANUAL_START_RAKUTEN_RECORDING"
        const val ACTION_MANUAL_STOP = "com.fumizo07.fmzlinkr.action.MANUAL_STOP_RAKUTEN_RECORDING"
        const val ACTION_REFRESH_SETTINGS = "com.fumizo07.fmzlinkr.action.REFRESH_RAKUTEN_SETTINGS"

        private const val RAKUTEN_PACKAGE = "jp.co.rakuten.mobile.rcs"
        private const val MONITORING_CHANNEL_ID = "fmzlinkr_monitoring"
        private const val RECORDING_CONTROL_CHANNEL_ID = "fmzlinkr_recording_controls"
        private const val RECORDING_EVENT_CHANNEL_ID = "fmzlinkr_recording_events"
        private const val FOREGROUND_NOTIFICATION_ID = 7012
        private const val RECORDING_EVENT_NOTIFICATION_ID = 7013
        private const val RECORDING_CONTROL_NOTIFICATION_ID = 7014
        private const val MODE_POLL_MS = 750L
        private const val END_DEBOUNCE_MS = 1_500L
        private const val INCOMING_HINT_VALID_MS = 60_000L
        private const val OGG_MIME = "audio/ogg"
        private const val M4A_MIME = "audio/mp4"

        // FMZlinkR filename convention requested by the project owner.
        // Note that these tokens intentionally differ from the usual English direction naming.
        private const val FILE_DIRECTION_OUTGOING = "in"
        private const val FILE_DIRECTION_INCOMING = "out"

        fun enable(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RakutenLinkRecordingService::class.java).setAction(ACTION_ENABLE),
            )
        }

        fun disable(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, RakutenLinkRecordingService::class.java).setAction(ACTION_DISABLE),
                )
            }
        }

        fun refreshSettings(context: Context) {
            if (!FmzPreferences(context).isMonitoringEnabled()) return
            runCatching {
                context.startService(
                    Intent(context, RakutenLinkRecordingService::class.java).setAction(ACTION_REFRESH_SETTINGS),
                )
            }
        }
    }

    private enum class CallKind { NONE, CHECKING, RAKUTEN, UNKNOWN, OTHER }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: FmzPreferences
    private lateinit var audioManager: AudioManager
    private lateinit var shizukuManager: ShizukuConnectionManager
    private lateinit var overlay: FmzOverlayController

    @Volatile private var shellService: IShellService? = null
    @Volatile private var armed = false
    @Volatile private var binding = false
    @Volatile private var callActive = false
    @Volatile private var lateArmCall = false
    @Volatile private var recording = false
    @Volatile private var startingRecording = false
    @Volatile private var callKind = CallKind.NONE

    private var bindJob: Job? = null
    private var monitoringGeneration = 0L
    private var callGeneration = 0L
    private var currentUri: Uri? = null
    private var endPending = false
    private var lastObservedMode = Int.MIN_VALUE
    private var lastRingtoneAtElapsedMs = 0L
    private var currentDirectionToken = FILE_DIRECTION_OUTGOING

    private val modePoll = object : Runnable {
        override fun run() {
            evaluateMode(audioManager.mode)
            if (prefs.isMonitoringEnabled()) handler.postDelayed(this, MODE_POLL_MS)
        }
    }

    private val endRunnable = Runnable {
        val mode = audioManager.mode
        AppLogger.i("FMZlinkR call-end debounce fired: mode=$mode callActive=$callActive")
        if (mode != AudioManager.MODE_IN_COMMUNICATION) {
            // Keep endPending=true until handleCallEnded() runs so the 750ms mode poll cannot schedule
            // a duplicate 1.5s debounce in the small window before the coroutine is dispatched.
            scope.launch { handleCallEnded() }
        } else {
            endPending = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = FmzPreferences(this)
        audioManager = getSystemService(AudioManager::class.java)
        overlay = FmzOverlayController(this)
        createNotificationChannels()
        shizukuManager = ShizukuConnectionManager(this) {
            AppLogger.w("FMZlinkR lost its Shizuku UserService connection")
            monitoringGeneration++
            callGeneration++
            bindJob?.cancel()
            bindJob = null
            shellService = null
            armed = false
            binding = false
            recording = false
            startingRecording = false
            callActive = false
            lateArmCall = false
            callKind = CallKind.NONE
            currentUri = null
            endPending = false
            lastRingtoneAtElapsedMs = 0L
            currentDirectionToken = FILE_DIRECTION_OUTGOING
            prefs.setMonitoringEnabled(false)
            handler.removeCallbacks(modePoll)
            handler.removeCallbacks(endRunnable)
            overlay.hide()
            hideRecordingControlNotification()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        startForegroundCompat(buildMonitoringNotification("Rakuten Link録音待機を準備中"))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISABLE -> disableMonitoring()
            ACTION_MANUAL_START -> scope.launch { startRecording(manual = true) }
            ACTION_MANUAL_STOP -> scope.launch { stopRecording(keepControls = true) }
            ACTION_REFRESH_SETTINGS -> refreshVisibleControls()
            ACTION_ENABLE, null -> {
                if (intent?.action == ACTION_ENABLE || prefs.isMonitoringEnabled()) enableMonitoring()
                else disableMonitoring()
            }
        }
        return START_STICKY
    }

    private fun enableMonitoring() {
        if (armed) {
            prefs.setMonitoringEnabled(true)
            refreshVisibleControls()
            return
        }
        if (binding) {
            prefs.setMonitoringEnabled(true)
            return
        }

        val folder = prefs.getRecordingFolderUri()
        when {
            !ShizukuConnectionManager.isAvailable() -> failEnable("Shizukuが起動していません")
            !ShizukuConnectionManager.hasPermission(this) -> failEnable("Shizuku権限がありません")
            !SafHelper.isFolderValid(this, folder) -> failEnable("録音保存先を選択してください")
            else -> bindAndArm()
        }
    }

    private fun bindAndArm() {
        val generation = ++monitoringGeneration
        prefs.setMonitoringEnabled(true)
        binding = true
        updateMonitoringNotification("Rakuten Link録音待機を準備中")

        bindJob?.cancel()
        bindJob = scope.launch {
            var boundService: IShellService? = null
            try {
                val service = runCatching {
                    withContext(Dispatchers.IO) { shizukuManager.getShellService() }
                }.onFailure {
                    if (it !is CancellationException) {
                        AppLogger.e("FMZlinkR could not bind Shizuku UserService: ${it.message}", it)
                    }
                }.getOrNull()

                boundService = service
                if (!isMonitoringGenerationCurrent(generation)) return@launch
                if (service == null) {
                    failEnable("Shizuku UserServiceへ接続できません")
                    return@launch
                }

                val didArm = runCatching {
                    withContext(Dispatchers.IO) { service.armVoipCapture() }
                }.onFailure {
                    if (it !is CancellationException) {
                        AppLogger.e("FMZlinkR AudioPolicy arm failed: ${it.message}", it)
                    }
                }.getOrDefault(false)

                if (!isMonitoringGenerationCurrent(generation)) return@launch
                if (!didArm) {
                    failEnable("AudioPolicyを登録できませんでした")
                    return@launch
                }

                shellService = service
                armed = true
                binding = false
                lateArmCall = audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
                callActive = lateArmCall
                callGeneration++
                callKind = if (lateArmCall) CallKind.UNKNOWN else CallKind.NONE
                lastObservedMode = Int.MIN_VALUE
                endPending = false
                lastRingtoneAtElapsedMs = 0L
                currentDirectionToken = FILE_DIRECTION_OUTGOING
                handler.removeCallbacks(modePoll)
                handler.removeCallbacks(endRunnable)
                handler.post(modePoll)

                if (lateArmCall) {
                    updateMonitoringNotification("録音待機中（現在の通話は次回から有効）")
                    AppLogger.w("FMZlinkR armed after the current VoIP track was created; current call skipped")
                } else {
                    updateMonitoringNotification()
                    AppLogger.i("FMZlinkR ready: AudioPolicy armed before the next call")
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                val stillCurrent = isMonitoringGenerationCurrent(generation)
                if (!stillCurrent) {
                    // The remote call may have armed the policy just as this coroutine was cancelled.
                    // Always disarm a stale bound service instead of relying on a local success flag.
                    val staleService = boundService
                    if (staleService != null) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            runCatching { staleService.stopVoipRecording() }
                            runCatching { staleService.disarmVoipCapture() }
                        }
                    }
                    shizukuManager.unbind()
                }
                if (generation == monitoringGeneration) {
                    binding = false
                    bindJob = null
                }
            }
        }
    }

    private fun isMonitoringGenerationCurrent(generation: Long): Boolean =
        generation == monitoringGeneration && prefs.isMonitoringEnabled()

    private fun failEnable(message: String) {
        AppLogger.e("FMZlinkR monitoring unavailable: $message")
        monitoringGeneration++
        callGeneration++
        prefs.setMonitoringEnabled(false)
        bindJob = null
        armed = false
        binding = false
        shellService = null
        callActive = false
        lateArmCall = false
        callKind = CallKind.NONE
        currentUri = null
        endPending = false
        lastRingtoneAtElapsedMs = 0L
        currentDirectionToken = FILE_DIRECTION_OUTGOING
        handler.removeCallbacks(modePoll)
        handler.removeCallbacks(endRunnable)
        overlay.hide()
        hideRecordingControlNotification()
        shizukuManager.unbind()
        showRecordingEvent("録音待機を開始できません", message)
        handler.postDelayed({
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 1_500L)
    }

    private fun disableMonitoring() {
        monitoringGeneration++
        callGeneration++
        prefs.setMonitoringEnabled(false)
        bindJob?.cancel()
        bindJob = null
        handler.removeCallbacks(modePoll)
        handler.removeCallbacks(endRunnable)
        endPending = false
        overlay.hide()
        hideRecordingControlNotification()

        val service = shellService
        shellService = null
        armed = false
        binding = false
        callActive = false
        lateArmCall = false
        recording = false
        startingRecording = false
        callKind = CallKind.NONE
        currentUri = null
        lastRingtoneAtElapsedMs = 0L
        currentDirectionToken = FILE_DIRECTION_OUTGOING

        scope.launch {
            if (service != null) {
                withContext(Dispatchers.IO) {
                    runCatching { service.stopVoipRecording() }
                    runCatching { service.disarmVoipCapture() }
                }
            }
            shizukuManager.unbind()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun evaluateMode(mode: Int) {
        if (mode != lastObservedMode) {
            AppLogger.i("FMZlinkR audio mode changed: $lastObservedMode -> $mode callActive=$callActive")
            lastObservedMode = mode
        }
        if (!armed) return

        if (mode == AudioManager.MODE_RINGTONE && !callActive) {
            lastRingtoneAtElapsedMs = SystemClock.elapsedRealtime()
        }

        if (mode == AudioManager.MODE_IN_COMMUNICATION) {
            if (endPending) {
                handler.removeCallbacks(endRunnable)
                endPending = false
                AppLogger.d("FMZlinkR call-end debounce cancelled because IN_COMMUNICATION resumed")
            }
            if (!callActive) {
                val now = SystemClock.elapsedRealtime()
                val looksIncoming = lastRingtoneAtElapsedMs > 0L &&
                    now - lastRingtoneAtElapsedMs in 0..INCOMING_HINT_VALID_MS
                currentDirectionToken = if (looksIncoming) FILE_DIRECTION_INCOMING else FILE_DIRECTION_OUTGOING
                lastRingtoneAtElapsedMs = 0L
                AppLogger.i("FMZlinkR filename direction token=$currentDirectionToken incomingHint=$looksIncoming")

                callActive = true
                callKind = CallKind.CHECKING
                val generation = ++callGeneration
                scope.launch { handleCallStarted(generation) }
            }
        } else if (callActive && !endPending) {
            endPending = true
            AppLogger.i("FMZlinkR scheduling call end: mode=$mode debounce=${END_DEBOUNCE_MS}ms")
            handler.postDelayed(endRunnable, END_DEBOUNCE_MS)
        }
    }

    private suspend fun handleCallStarted(generation: Long) {
        if (lateArmCall) return
        val service = shellService ?: return
        updateMonitoringNotification()

        val rakutenUid = runCatching {
            packageManager.getApplicationInfo(RAKUTEN_PACKAGE, 0).uid
        }.getOrNull()
        val ownerUid = runCatching {
            withContext(Dispatchers.IO) { service.voipCallAppUid() }
        }.onFailure {
            AppLogger.w("FMZlinkR could not resolve VoIP owner uid: ${it.message}")
        }.getOrDefault(-1)

        if (
            generation != callGeneration ||
            !callActive ||
            audioManager.mode != AudioManager.MODE_IN_COMMUNICATION ||
            !armed ||
            service !== shellService
        ) {
            AppLogger.d("FMZlinkR discarded stale VoIP classification result")
            return
        }

        callKind = when {
            ownerUid < 0 -> CallKind.UNKNOWN
            rakutenUid != null && ownerUid == rakutenUid -> CallKind.RAKUTEN
            else -> CallKind.OTHER
        }
        AppLogger.i("FMZlinkR VoIP classification: ownerUid=$ownerUid rakutenUid=$rakutenUid kind=$callKind")

        when (callKind) {
            CallKind.RAKUTEN -> {
                refreshVisibleControls()
                if (prefs.isAutoRecordEnabled()) startRecording(manual = false)
            }
            CallKind.UNKNOWN -> {
                // Unknown is never auto-recorded, but manual capture remains available for OEMs
                // whose audio service does not expose the playback owner UID reliably.
                refreshVisibleControls()
            }
            CallKind.OTHER -> {
                overlay.hide()
                hideRecordingControlNotification()
                updateMonitoringNotification()
            }
            else -> Unit
        }
    }

    private suspend fun handleCallEnded() {
        if (!callActive) {
            endPending = false
            return
        }
        endPending = false
        handler.removeCallbacks(endRunnable)
        callGeneration++
        if (recording || startingRecording) stopRecording(keepControls = false)
        callActive = false
        lateArmCall = false
        callKind = CallKind.NONE
        currentDirectionToken = FILE_DIRECTION_OUTGOING
        overlay.hide()
        hideRecordingControlNotification()
        AppLogger.i("FMZlinkR call ended; overlay hidden and monitoring remains armed=$armed")
        if (armed) updateMonitoringNotification()
    }

    private suspend fun startRecording(manual: Boolean) {
        if (recording || startingRecording || !armed || lateArmCall) return
        if (!callActive || audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) return
        if (callKind == CallKind.OTHER || callKind == CallKind.CHECKING || callKind == CallKind.NONE) return
        if (!manual && callKind != CallKind.RAKUTEN) return

        val service = shellService ?: return
        val generation = callGeneration
        val folder = prefs.getRecordingFolderUri() ?: return
        if (!SafHelper.isFolderValid(this, folder)) {
            showRecordingEvent("録音開始に失敗", "録音保存先へ書き込めません")
            return
        }

        val codec = prefs.getAudioCodec()
        val bitRate = prefs.getAudioBitRate()
        val fileName = buildRecordingFileName(codec)
        val mimeType = if (codec == FmzPreferences.AUDIO_CODEC_AAC) M4A_MIME else OGG_MIME

        startingRecording = true
        val saf = SafHelper.createAudioFile(this, folder, fileName, mimeType)
        if (saf == null) {
            startingRecording = false
            showRecordingEvent("録音開始に失敗", "録音ファイルを作成できません")
            return
        }

        val started = runCatching {
            withContext(Dispatchers.IO) {
                service.startVoipRecording(bitRate, codec, saf.descriptor)
            }
        }.onFailure {
            AppLogger.e("FMZlinkR VoIP capture start failed: ${it.message}", it)
        }.getOrDefault(false)
        runCatching { saf.descriptor.close() }
        startingRecording = false

        if (!started) {
            runCatching { DocumentFile.fromSingleUri(this, saf.uri)?.delete() }
            showRecordingEvent("録音開始に失敗", "次の通話前から録音待機をONにしてください")
            return
        }

        if (
            generation != callGeneration ||
            !callActive ||
            audioManager.mode != AudioManager.MODE_IN_COMMUNICATION ||
            service !== shellService
        ) {
            runCatching { withContext(Dispatchers.IO) { service.stopVoipRecording() } }
            runCatching { DocumentFile.fromSingleUri(this, saf.uri)?.delete() }
            return
        }

        currentUri = saf.uri
        recording = true
        AppLogger.i(
            "FMZlinkR Rakuten Link recording started -> ${saf.displayName}, manual=$manual codec=$codec bitRate=$bitRate",
        )
        showRecordingEvent("録音開始", saf.displayName)
        refreshVisibleControls()
    }

    private suspend fun stopRecording(keepControls: Boolean) {
        if (!recording && !startingRecording) return
        val service = shellService
        recording = false
        startingRecording = false

        if (service != null) {
            runCatching { withContext(Dispatchers.IO) { service.stopVoipRecording() } }
                .onFailure { AppLogger.e("FMZlinkR VoIP stop failed: ${it.message}", it) }
        }
        val nearHeard = if (service != null) {
            runCatching { withContext(Dispatchers.IO) { service.voipNearPartyHeard() } }.getOrDefault(false)
        } else {
            false
        }
        val farHeard = if (service != null) {
            runCatching { withContext(Dispatchers.IO) { service.voipFarPartyHeard() } }.getOrDefault(false)
        } else {
            false
        }

        val uri = currentUri
        currentUri = null
        val size = uri?.let { DocumentFile.fromSingleUri(this, it)?.length() } ?: -1L
        AppLogger.i(
            "FMZlinkR Rakuten Link recording stopped: size=$size nearPartyHeard=$nearHeard farPartyHeard=$farHeard",
        )

        val eventText = when {
            size <= 0L -> "録音停止。ファイルサイズを確認してください"
            !nearHeard && !farHeard -> "録音停止。自分側・相手側とも音声を検出できませんでした"
            !nearHeard -> "録音停止。自分側音声を検出できませんでした"
            !farHeard -> "録音停止。相手側音声を検出できませんでした"
            else -> "録音を保存しました"
        }
        showRecordingEvent("録音停止", eventText)

        if (keepControls && callActive) {
            refreshVisibleControls()
        } else {
            overlay.hide()
            hideRecordingControlNotification()
            if (armed) updateMonitoringNotification()
        }
    }

    private fun buildRecordingFileName(codec: String): String {
        val date = SimpleDateFormat("yyyyMMdd_HHmm_ss.SSS", Locale.CANADA).format(Date())
        val template = prefs.getFileNameTemplate().ifBlank { FmzPreferences.DEFAULT_FILE_NAME_TEMPLATE }
        val raw = template
            .replace("{date}", date)
            .replace("{direction}", currentDirectionToken)
            .replace("{app}", "RakutenLink")

        val illegalCharacters = "\\/:*?\"<>|\r\n"
        val sanitized = raw
            .map { if (it in illegalCharacters) '_' else it }
            .joinToString("")
            .trim()
            .trim('.')
            .ifBlank { "${date}_${currentDirectionToken}_RakutenLink" }

        val baseName = when {
            sanitized.endsWith(".ogg", ignoreCase = true) -> sanitized.dropLast(4)
            sanitized.endsWith(".m4a", ignoreCase = true) -> sanitized.dropLast(4)
            else -> sanitized
        }
        val extension = if (codec == FmzPreferences.AUDIO_CODEC_AAC) ".m4a" else ".ogg"
        return "$baseName$extension"
    }

    private fun refreshVisibleControls() {
        if (!armed) {
            hideRecordingControlNotification()
            return
        }
        if (!callActive) {
            overlay.hide()
            hideRecordingControlNotification()
            updateMonitoringNotification()
            return
        }

        when (callKind) {
            CallKind.RAKUTEN, CallKind.UNKNOWN -> {
                if (prefs.isOverlayEnabled()) {
                    overlay.show(recording) {
                        val action = if (recording) ACTION_MANUAL_STOP else ACTION_MANUAL_START
                        startService(Intent(this, RakutenLinkRecordingService::class.java).setAction(action))
                    }
                } else {
                    overlay.hide()
                }
                showRecordingControlNotification()
                updateMonitoringNotification()
            }
            CallKind.OTHER -> {
                overlay.hide()
                hideRecordingControlNotification()
                updateMonitoringNotification()
            }
            CallKind.CHECKING -> {
                hideRecordingControlNotification()
                updateMonitoringNotification()
            }
            CallKind.NONE -> hideRecordingControlNotification()
        }
    }

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val monitoringChannel = NotificationChannel(
            MONITORING_CHANNEL_ID,
            "FMZlinkR 録音待機",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Rakuten Link録音待機の継続通知"
        }
        val recordingControlChannel = NotificationChannel(
            RECORDING_CONTROL_CHANNEL_ID,
            "FMZlinkR 録音操作",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Rakuten Link通話中の録音開始・停止操作"
        }
        val recordingEventChannel = NotificationChannel(
            RECORDING_EVENT_CHANNEL_ID,
            "FMZlinkR 録音開始・停止",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Rakuten Link録音の開始・停止・エラー通知"
        }
        notificationManager.createNotificationChannels(
            listOf(monitoringChannel, recordingControlChannel, recordingEventChannel),
        )
    }

    private fun buildMonitoringNotification(text: String = "Rakuten Link録音待機中"): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, MONITORING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("FMZlinkR")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        val disableIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, RakutenLinkRecordingService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(R.drawable.ic_stop, "待機停止", disableIntent)
        return builder.build()
    }

    private fun updateMonitoringNotification(text: String = "Rakuten Link録音待機中") {
        startForegroundCompat(buildMonitoringNotification(text))
    }

    private fun showRecordingControlNotification() {
        if (
            !callActive ||
            lateArmCall ||
            (callKind != CallKind.RAKUTEN && callKind != CallKind.UNKNOWN)
        ) {
            hideRecordingControlNotification()
            return
        }

        val openIntent = PendingIntent.getActivity(
            this,
            5,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val action = if (recording) ACTION_MANUAL_STOP else ACTION_MANUAL_START
        val label = if (recording) "録音停止" else "録音開始"
        val icon = if (recording) R.drawable.ic_stop else R.drawable.ic_mic
        val actionIntent = PendingIntent.getService(
            this,
            6,
            Intent(this, RakutenLinkRecordingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, RECORDING_CONTROL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("FMZlinkR 録音操作")
            .setContentText(if (recording) "Rakuten Link録音中" else "Rakuten Link通話中")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(icon, label, actionIntent)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(RECORDING_CONTROL_NOTIFICATION_ID, notification)
    }

    private fun hideRecordingControlNotification() {
        getSystemService(NotificationManager::class.java)
            .cancel(RECORDING_CONTROL_NOTIFICATION_ID)
    }

    private fun showRecordingEvent(title: String, text: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            4,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, RECORDING_EVENT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("FMZlinkR - $title")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(RECORDING_EVENT_NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        monitoringGeneration++
        callGeneration++
        bindJob?.cancel()
        bindJob = null
        handler.removeCallbacks(modePoll)
        handler.removeCallbacks(endRunnable)
        endPending = false
        overlay.hide()
        hideRecordingControlNotification()

        // Fallback for Android destroying the service without ACTION_DISABLE. Do cleanup before
        // cancelling the coroutine scope or removing FMZlinkR's own UserService.
        val service = shellService
        shellService = null
        if (service != null) {
            runCatching { service.stopVoipRecording() }
                .onFailure { AppLogger.w("FMZlinkR teardown stop failed: ${it.message}") }
            runCatching { service.disarmVoipCapture() }
                .onFailure { AppLogger.w("FMZlinkR teardown disarm failed: ${it.message}") }
        }
        shizukuManager.unbind()
        scope.cancel()
        super.onDestroy()
    }
}
