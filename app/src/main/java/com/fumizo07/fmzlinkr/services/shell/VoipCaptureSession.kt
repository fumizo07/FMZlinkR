/*
 * FMZlinkR VoIP capture.
 * Adapted from CallVault VoipCaptureSession.
 * Copyright (C) 2026-present The CallVault Authors
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.services.shell

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import com.fumizo07.fmzlinkr.utils.AppLogger
import com.fumizo07.fmzlinkr.utils.PcmDownmix
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Far party from AudioPolicy + near party from MIC, downmixed to one encoded audio stream. */
internal class VoipCaptureSession(
    audioCodec: String,
    private val bitRate: Int,
    private val outFd: ParcelFileDescriptor,
) {
    private val outputCodec = OutputCodec.fromKey(audioCodec)
    private val stopRequested = AtomicBoolean(false)

    @Volatile var farPartyHeard: Boolean = false
        private set
    @Volatile var nearPartyHeard: Boolean = false
        private set
    @Volatile private var farRecord: AudioRecord? = null
    @Volatile private var nearRecord: AudioRecord? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var muxer: MediaMuxer? = null
    @Volatile private var muxThread: Thread? = null

    fun start() {
        try {
            startInternal()
        } catch (t: Throwable) {
            // ShellService/caller owns outFd until a session starts successfully.
            cleanupPartial()
            throw t
        }
    }

    private fun startInternal() {
        val far = VoipAudioPolicy.createSink()
            ?: throw IllegalStateException("VoIP far-party sink unavailable (policy not armed?)")
        farRecord = far

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) throw IllegalStateException("VoIP mic minBufferSize=$minBuf")

        val near = createShellMic(minBuf * BUFFER_FACTOR)
            ?: throw IllegalStateException("VoIP mic capture failed to initialise with shell attribution")
        nearRecord = near
        AppLogger.i("VoIP null-Context MIC initialised successfully")

        val format = MediaFormat.createAudioFormat(outputCodec.mimeType, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            if (outputCodec == OutputCodec.AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val enc = MediaCodec.createEncoderByType(outputCodec.mimeType).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        encoder = enc

        // Create the muxer last, matching the proven capture ordering.
        val mux = MediaMuxer(outFd.fileDescriptor, outputCodec.muxerOutputFormat)
        muxer = mux

        enc.start()
        far.startRecording()
        near.startRecording()
        if (far.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("VoIP far-party AudioRecord did not enter RECORDSTATE_RECORDING")
        }
        if (near.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("VoIP MIC AudioRecord did not enter RECORDSTATE_RECORDING")
        }
        AppLogger.i("VoIP capture started: codec=${outputCodec.key} rate=$SAMPLE_RATE bitRate=$bitRate")

        muxThread = Thread {
            runCatching { captureLoop(near, far, enc, mux) }
                .onFailure { AppLogger.w("VoIP capture loop ended: ${it.message}", it) }
        }.apply {
            isDaemon = true
            name = "fmz-voip-capture"
            start()
        }
    }

    /**
     * AudioRecord.Builder keeps its attribution Context null until setContext() is explicitly called.
     * That is required inside Shizuku UserService: the process is shell uid 2000, while the Application
     * object belongs to FMZlinkR's package. Using the legacy AudioRecord constructor would implicitly
     * attach that Application Context and can make native AudioRecord attribution validation fail.
     */
    @Suppress("MissingPermission")
    private fun createShellMic(bufferSize: Int): AudioRecord? = runCatching {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }.onFailure {
        AppLogger.e("VoIP null-Context MIC construction failed: ${it.message}", it)
    }.getOrNull()

    private fun captureLoop(near: AudioRecord, far: AudioRecord, enc: MediaCodec, mux: MediaMuxer) {
        val qNear: BlockingQueue<ByteArray> = ArrayBlockingQueue(QUEUE_CHUNKS)
        val qFar: BlockingQueue<ByteArray> = ArrayBlockingQueue(QUEUE_CHUNKS)
        val readers = listOf(feeder(near, qNear, "near"), feeder(far, qFar, "far"))
        readers.forEach { it.start() }

        val silence = ByteArray(CHUNK_BYTES)
        val stereo = ByteArray(CHUNK_BYTES * 2)
        val mono = ByteArray(CHUNK_BYTES)
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var totalFrames = 0L
        var substituted = 0L

        try {
            while (!stopRequested.get()) {
                val n = qNear.poll(CHUNK_WAIT_MS, TimeUnit.MILLISECONDS) ?: silence.also { substituted++ }
                val f = qFar.poll(CHUNK_WAIT_MS, TimeUnit.MILLISECONDS) ?: silence

                if (!nearPartyHeard && peakPcm16(n) > NEAR_SILENCE_THRESHOLD) nearPartyHeard = true
                if (!farPartyHeard && peakPcm16(f) > FAR_SILENCE_THRESHOLD) farPartyHeard = true

                var o = 0
                for (i in 0 until CHUNK_BYTES step 2) {
                    stereo[o] = n[i]
                    stereo[o + 1] = n[i + 1]
                    stereo[o + 2] = f[i]
                    stereo[o + 3] = f[i + 1]
                    o += 4
                }

                val len = PcmDownmix.stereoToMono(stereo, stereo.size, mono)
                var inputIndex = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                while (inputIndex < 0 && !stopRequested.get()) {
                    muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
                    inputIndex = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                }
                if (inputIndex < 0) break

                enc.getInputBuffer(inputIndex)!!.apply {
                    clear()
                    put(mono, 0, len)
                }
                enc.queueInputBuffer(
                    inputIndex,
                    0,
                    len,
                    totalFrames * 1_000_000L / SAMPLE_RATE,
                    0,
                )
                totalFrames += len / 2
                muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
            }

            // Queue EOS only when an input buffer is actually available. Never wait forever for an
            // EOS that was not queued, and bound the final EOS drain even after a successful queue.
            val eosInputIndex = enc.dequeueInputBuffer(END_OF_STREAM_INPUT_TIMEOUT_US)
            if (eosInputIndex >= 0) {
                enc.queueInputBuffer(
                    eosInputIndex,
                    0,
                    0,
                    totalFrames * 1_000_000L / SAMPLE_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                drainEncoder(enc, mux, info, muxerStarted, drainToEos = true)
            } else {
                AppLogger.w("VoIP encoder EOS input buffer unavailable; draining ready output without waiting for EOS")
                drainEncoder(enc, mux, info, muxerStarted, drainToEos = false)
            }

            AppLogger.i(
                "VoIP capture finished: ${totalFrames / SAMPLE_RATE}s, " +
                    "$substituted silence-filled chunks, nearPartyHeard=$nearPartyHeard, farPartyHeard=$farPartyHeard",
            )
            if (!nearPartyHeard) AppLogger.w("Near party was never audible on the MIC capture")
            if (!farPartyHeard) AppLogger.w("Far party was never audible on the AudioPolicy sink")
        } finally {
            readers.forEach { it.interrupt() }
        }
    }

    private fun feeder(
        initialRecord: AudioRecord,
        queue: BlockingQueue<ByteArray>,
        tag: String,
    ) = Thread {
        val buffer = ByteArray(CHUNK_BYTES)
        var record = initialRecord
        var silentChunks = 0

        while (!stopRequested.get()) {
            var offset = 0
            while (offset < CHUNK_BYTES && !stopRequested.get()) {
                val read = record.read(buffer, offset, CHUNK_BYTES - offset)
                if (read <= 0) {
                    AppLogger.d("VoIP $tag read=$read, feeder ending")
                    return@Thread
                }
                offset += read
            }
            if (offset < CHUNK_BYTES) return@Thread

            if (tag == "near") {
                if (isAllZero(buffer)) silentChunks++ else silentChunks = 0
                if (silentChunks >= SILENT_CHUNKS_BEFORE_RETAKE) {
                    silentChunks = 0
                    val fresh = retakeMic(record)
                    if (fresh != null) {
                        record = fresh
                        nearRecord = fresh
                    }
                }
            }
            queue.offer(buffer.copyOf())
        }
    }.apply {
        isDaemon = true
        name = "fmz-voip-$tag"
    }

    private fun isAllZero(buffer: ByteArray): Boolean {
        for (byte in buffer) if (byte.toInt() != 0) return false
        return true
    }

    private fun peakPcm16(buffer: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < buffer.size) {
            val sample = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
            val absolute = if (sample == Short.MIN_VALUE.toInt()) Short.MAX_VALUE.toInt() else kotlin.math.abs(sample)
            if (absolute > peak) peak = absolute
            i += 2
        }
        return peak
    }

    private fun retakeMic(current: AudioRecord): AudioRecord? {
        AppLogger.i("VoIP near capture was digitally silent; re-taking MIC")
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null

        val fresh = createShellMic(minBuf * BUFFER_FACTOR)
        if (fresh == null || fresh.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.w("VoIP MIC re-take failed to initialise; keeping current capture")
            runCatching { fresh?.release() }
            return null
        }

        val freshStarted = runCatching {
            fresh.startRecording()
            fresh.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.onFailure {
            AppLogger.w("VoIP MIC re-take could not start: ${it.message}", it)
        }.getOrDefault(false)
        if (!freshStarted) {
            AppLogger.w("VoIP MIC re-take did not enter RECORDSTATE_RECORDING; keeping current capture")
            runCatching { fresh.stop() }
            runCatching { fresh.release() }
            return null
        }

        // Only retire the proven-working old MIC after the replacement is actively recording.
        runCatching { current.stop() }
        runCatching { current.release() }
        return fresh
    }

    private fun drainEncoder(
        enc: MediaCodec,
        mux: MediaMuxer,
        info: MediaCodec.BufferInfo,
        muxerStartedIn: Boolean,
        drainToEos: Boolean = false,
    ): Boolean {
        var muxerStarted = muxerStartedIn
        // There is exactly one muxer track. MediaMuxer assigns its first track index as zero.
        var track = if (muxerStarted) 0 else -1
        val eosDeadlineNs = if (drainToEos) {
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(END_OF_STREAM_DRAIN_TIMEOUT_MS)
        } else {
            Long.MAX_VALUE
        }

        while (true) {
            val outputIndex = enc.dequeueOutputBuffer(
                info,
                if (drainToEos) END_OF_STREAM_DEQUEUE_TIMEOUT_US else 0,
            )
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = mux.addTrack(enc.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (drainToEos && System.nanoTime() < eosDeadlineNs) continue
                    if (drainToEos) AppLogger.w("VoIP encoder EOS drain timed out; closing recording deterministically")
                    return muxerStarted
                }
                outputIndex >= 0 -> {
                    val output = enc.getOutputBuffer(outputIndex)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        mux.writeSampleData(track, output, info)
                    }
                    val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    enc.releaseOutputBuffer(outputIndex, false)
                    if (endOfStream) return muxerStarted
                }
            }
        }
    }

    fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        val thread = muxThread
        runCatching { thread?.join(STOP_JOIN_MS) }
        if (thread?.isAlive == true) {
            AppLogger.w("VoIP capture thread did not stop within ${STOP_JOIN_MS}ms; forcing resource shutdown")
            thread.interrupt()
        }
        runCatching { farRecord?.stop() }
        runCatching { farRecord?.release() }
        runCatching { nearRecord?.stop() }
        runCatching { nearRecord?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { muxer?.stop() } // Finalises the selected container when the muxer was started.
        runCatching { muxer?.release() }
        runCatching { outFd.close() }
        farRecord = null
        nearRecord = null
        encoder = null
        muxer = null
        muxThread = null
        AppLogger.i("VoIP capture stopped")
    }

    private fun cleanupPartial() {
        runCatching { farRecord?.stop() }
        runCatching { farRecord?.release() }
        runCatching { nearRecord?.stop() }
        runCatching { nearRecord?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { muxer?.release() }
        farRecord = null
        nearRecord = null
        encoder = null
        muxer = null
    }

    private enum class OutputCodec(
        val key: String,
        val mimeType: String,
        val muxerOutputFormat: Int,
    ) {
        OPUS("opus", MediaFormat.MIMETYPE_AUDIO_OPUS, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG),
        AAC("aac", MediaFormat.MIMETYPE_AUDIO_AAC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        companion object {
            fun fromKey(key: String): OutputCodec = when (key.lowercase()) {
                AAC.key -> AAC
                else -> OPUS
            }
        }
    }

    private companion object {
        private const val SILENT_CHUNKS_BEFORE_RETAKE = 15
        private const val SAMPLE_RATE = VoipAudioPolicy.SAMPLE_RATE
        private const val BUFFER_FACTOR = 4
        private const val CHUNK_FRAMES = 960
        private const val CHUNK_BYTES = CHUNK_FRAMES * 2
        private const val QUEUE_CHUNKS = 400
        private const val CHUNK_WAIT_MS = 120L
        private const val MAX_INPUT_SIZE = 16_384
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val END_OF_STREAM_INPUT_TIMEOUT_US = 100_000L
        private const val END_OF_STREAM_DEQUEUE_TIMEOUT_US = 100_000L
        private const val END_OF_STREAM_DRAIN_TIMEOUT_MS = 1_500L
        private const val STOP_JOIN_MS = 3_000L
        private const val FAR_SILENCE_THRESHOLD = 100
        private const val NEAR_SILENCE_THRESHOLD = 100
    }
}
