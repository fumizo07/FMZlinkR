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
        private const val CHANNEL_ID = "fmzlinkr_recording"
        private const val NOTIFICATION_ID = 7012
        private const val MODE_POLL_MS = 750L
        private const val END_DEBOUNCE_MS = 1_500L
        private const val OPUS_BIT_RATE = 16_000
        private const val OGG_MIME = "audio/ogg"

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

    private val modePoll = object : Runnable {
        override fun run() {
            evaluateMode(audioManager.mode)
            if (prefs.isMonitoringEnabled()) handler.postDelayed(this, MODE_POLL_MS)
        }
    }

    private val endRunnable = Runnable {
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            scope.launch { handleCallEnded() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = FmzPreferences(this)
        audioManager = getSystemService(AudioManager::class.java)
        overlay = FmzOverlayController(this)
        createNotificationChannel()
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
            prefs.setMonitoringEnabled(false)
            handler.removeCallbacks(modePoll)
            handler.removeCallbacks(endRunnable)
            overlay.hide()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        startForegroundCompat(buildNotification("Rakuten Link録音待機を準備中"))
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
        updateNotification("Shizuku UserServiceへ接続中")

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
                handler.removeCallbacks(modePoll)
                handler.post(modePoll)

                if (lateArmCall) {
                    updateNotification("現在の通話には待機開始が遅いため、次の通話から録音できます")
                    AppLogger.w("FMZlinkR armed after the current VoIP track was created; current call skipped")
                } else {
                    updateNotification("Rakuten Link録音待機中")
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
        shizukuManager.unbind()
        updateNotification(message)
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
        overlay.hide()

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
        if (!armed) return
        if (mode == AudioManager.MODE_IN_COMMUNICATION) {
            handler.removeCallbacks(endRunnable)
            if (!callActive) {
                callActive = true
                callKind = CallKind.CHECKING
                val generation = ++callGeneration
                scope.launch { handleCallStarted(generation) }
            }
        } else if (callActive) {
            handler.removeCallbacks(endRunnable)
            handler.postDelayed(endRunnable, END_DEBOUNCE_MS)
        }
    }

    private suspend fun handleCallStarted(generation: Long) {
        if (lateArmCall) return
        val service = shellService ?: return
        updateNotification("VoIP通話を確認中")

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
                updateNotification("VoIPアプリを特定できません。手動録音のみ利用できます")
            }
            CallKind.OTHER -> {
                overlay.hide()
                updateNotification("Rakuten Link以外のVoIP通話は録音しません")
            }
            else -> Unit
        }
    }

    private suspend fun handleCallEnded() {
        if (!callActive) return
        callGeneration++
        if (recording || startingRecording) stopRecording(keepControls = false)
        callActive = false
        lateArmCall = false
        callKind = CallKind.NONE
        overlay.hide()
        updateNotification(if (armed) "Rakuten Link録音待機中" else "録音待機停止")
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
            updateNotification("録音保存先へ書き込めません")
            return
        }

        startingRecording = true
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss.SSSZ", Locale.CANADA).format(Date())
        val fileName = "${stamp}_RakutenLink.ogg"
        val saf = SafHelper.createAudioFile(this, folder, fileName, OGG_MIME)
        if (saf == null) {
            startingRecording = false
            updateNotification("録音ファイルを作成できません")
            return
        }

        val started = runCatching {
            withContext(Dispatchers.IO) {
                service.startVoipRecording(OPUS_BIT_RATE, saf.descriptor)
            }
        }.onFailure {
            AppLogger.e("FMZlinkR VoIP capture start failed: ${it.message}", it)
        }.getOrDefault(false)
        runCatching { saf.descriptor.close() }
        startingRecording = false

        if (!started) {
            runCatching { DocumentFile.fromSingleUri(this, saf.uri)?.delete() }
            updateNotification("録音開始に失敗しました。次の通話前から待機をONにしてください")
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
        AppLogger.i("FMZlinkR Rakuten Link recording started -> ${saf.displayName}, manual=$manual")
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

        if (keepControls && callActive) {
            refreshVisibleControls()
            when {
                !nearHeard && !farHeard -> updateNotification("録音停止。自分側・相手側とも音声を検出できませんでした")
                !nearHeard -> updateNotification("録音停止。自分側音声を検出できませんでした")
                !farHeard -> updateNotification("録音停止。相手側音声を検出できませんでした")
            }
        } else {
            overlay.hide()
        }
    }

    private fun refreshVisibleControls() {
        if (!armed) return
        if (!callActive) {
            overlay.hide()
            updateNotification("Rakuten Link録音待機中")
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
                updateNotification(if (recording) "Rakuten Link録音中" else "Rakuten Link通話中 - 手動録音できます")
            }
            CallKind.OTHER -> {
                overlay.hide()
                updateNotification("Rakuten Link以外のVoIP通話は録音しません")
            }
            CallKind.CHECKING -> updateNotification("VoIP通話を確認中")
            CallKind.NONE -> Unit
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FMZlinkR 録音待機",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Rakuten Link録音待機と録音状態"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("FMZlinkR")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (callActive && !lateArmCall && (callKind == CallKind.RAKUTEN || callKind == CallKind.UNKNOWN)) {
            val action = if (recording) ACTION_MANUAL_STOP else ACTION_MANUAL_START
            val label = if (recording) "録音停止" else "録音開始"
            val icon = if (recording) R.drawable.ic_stop else R.drawable.ic_mic
            val actionIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, RakutenLinkRecordingService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(icon, label, actionIntent)
        }

        val disableIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, RakutenLinkRecordingService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.addAction(R.drawable.ic_stop, "待機停止", disableIntent)
        return builder.build()
    }

    private fun updateNotification(text: String) {
        startForegroundCompat(buildNotification(text))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        monitoringGeneration++
        callGeneration++
        bindJob?.cancel()
        bindJob = null
        handler.removeCallbacks(modePoll)
        handler.removeCallbacks(endRunnable)
        overlay.hide()

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
