/*
 * FMZlinkR VoIP capture.
 * Based on CallVault VoIP capture work.
 * Copyright (C) 2026-present The CallVault Authors
 * Copyright (C) 2026-present kitsumed (Med)
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.services.shell

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Binder
import com.fumizo07.fmzlinkr.utils.AppLogger
import java.lang.reflect.Constructor

/** Privileged far-party AudioPolicy. Must be armed before the VoIP playback track is created. */
internal object VoipAudioPolicy {
    private const val MIX_ROLE_PLAYERS = 0
    private const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
    private const val ROUTE_FLAG_LOOP_BACK_RENDER = 0x3
    const val SAMPLE_RATE = 48_000

    @Volatile private var policy: Any? = null
    @Volatile private var mix: Any? = null
    val isArmed: Boolean get() = policy != null

    @Synchronized
    fun arm(): Boolean {
        if (policy != null) return true
        return runCatching {
            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            ruleBuilderClass.getMethod("setTargetMixRole", Int::class.javaPrimitiveType)
                .invoke(ruleBuilder, MIX_ROLE_PLAYERS)
            ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
                .invoke(
                    ruleBuilder,
                    RULE_MATCH_ATTRIBUTE_USAGE,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build(),
                )
            runCatching {
                ruleBuilderClass.getMethod("voiceCommunicationCaptureAllowed", Boolean::class.javaPrimitiveType)
                    .invoke(ruleBuilder, true)
            }
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixConstructor: Constructor<*> = mixBuilderClass.getDeclaredConstructor(ruleClass).apply { isAccessible = true }
            val mixBuilder = mixConstructor.newInstance(rule)
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(
                mixBuilder,
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                .invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK_RENDER)
            val builtMix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            // Null Context is intentional for the shell-uid attribution path used by CallVault.
            val policyBuilder = policyBuilderClass
                .getConstructor(Context::class.java)
                .newInstance(*arrayOf<Any?>(null))
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, builtMix)
            val builtPolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

            val register = Class.forName("android.media.AudioManager")
                .getDeclaredMethod("registerAudioPolicyStatic", policyClass)
                .apply { isAccessible = true }
            val result = register.invoke(null, builtPolicy) as Int
            if (result != 0) {
                AppLogger.w("VoIP AudioPolicy registration rejected (rc=$result)")
                return@runCatching false
            }
            policy = builtPolicy
            mix = builtMix
            AppLogger.i("VoIP AudioPolicy armed: USAGE_VOICE_COMMUNICATION loopback-render ${SAMPLE_RATE}Hz")
            true
        }.onFailure {
            AppLogger.e("VoIP AudioPolicy arm failed: ${describeThrowable(it)}", it)
        }.getOrDefault(false)
    }

    @Synchronized
    fun disarm() {
        val current = policy ?: return
        policy = null
        mix = null
        runCatching {
            Class.forName("android.media.AudioManager")
                .getDeclaredMethod(
                    "unregisterAudioPolicyAsyncStatic",
                    Class.forName("android.media.audiopolicy.AudioPolicy"),
                )
                .apply { isAccessible = true }
                .invoke(null, current)
            AppLogger.i("VoIP AudioPolicy disarmed")
        }.onFailure { AppLogger.w("VoIP AudioPolicy disarm failed: ${describeThrowable(it)}", it) }
    }

    @Synchronized
    fun createSink(): AudioRecord? {
        val currentPolicy = policy ?: run {
            AppLogger.w("VoIP far-party sink requested with no armed AudioPolicy")
            return null
        }
        val currentMix = mix ?: run {
            AppLogger.w("VoIP far-party sink requested with no AudioMix")
            return null
        }
        AppLogger.i(
            "VoIP far-party sink creation requested uid=${android.os.Process.myUid()} " +
                "callingUid=${Binder.getCallingUid()}",
        )
        return runCatching {
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val record = policyClass.getMethod("createAudioRecordSink", mixClass)
                .invoke(currentPolicy, currentMix) as AudioRecord?

            if (record == null) {
                AppLogger.w("VoIP far-party sink creation returned null")
                return@runCatching null
            }
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                AppLogger.i("VoIP far-party sink initialised successfully")
                return@runCatching record
            }

            val failedState = record.state
            AppLogger.w(
                "VoIP standard far-party sink is uninitialised (state=$failedState); " +
                    "retrying with explicit null Context attribution",
            )
            val fallback = createNullContextSink(record)
            runCatching { record.release() }

            if (fallback == null || fallback.state != AudioRecord.STATE_INITIALIZED) {
                val fallbackState = fallback?.state
                runCatching { fallback?.release() }
                AppLogger.w("VoIP null-Context far-party sink failed to initialise (state=$fallbackState)")
                null
            } else {
                AppLogger.i("VoIP null-Context far-party sink initialised successfully")
                fallback
            }
        }.onFailure {
            AppLogger.e("VoIP far-party sink creation failed: ${describeThrowable(it)}", it)
        }.getOrNull()
    }

    /**
     * Shizuku UserService creates an Application object for the client package while the process still
     * runs as shell uid 2000. AudioPolicy.createAudioRecordSink() eventually calls the public
     * AudioRecord constructor, which automatically uses ActivityThread.currentApplication() as its
     * attribution Context. On devices that reject a shell uid + app-package attribution pair, that
     * leaves the AudioRecord in STATE_UNINITIALIZED.
     *
     * Recreate the exact failed sink parameters through AudioRecord's internal constructor but pass a
     * null Context explicitly. This matches the command-line/shell attribution path without starting a
     * daemon or altering Shizuku/adbd. The standard framework path is always attempted first.
     */
    private fun createNullContextSink(template: AudioRecord): AudioRecord? = runCatching {
        val attributes = AudioRecord::class.java
            .getMethod("getAudioAttributes")
            .invoke(template) as AudioAttributes
        val format = template.format
        val bufferSize = AudioRecord.getMinBufferSize(
            format.sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            format.encoding,
        )
        if (bufferSize <= 0) {
            throw IllegalStateException("VoIP null-Context sink minBufferSize=$bufferSize")
        }

        val constructor = AudioRecord::class.java.getDeclaredConstructor(
            AudioAttributes::class.java,
            AudioFormat::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Context::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        constructor.newInstance(
            attributes,
            format,
            bufferSize,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
            null,
            0,
            0,
        ) as AudioRecord
    }.onFailure {
        AppLogger.e("VoIP null-Context sink construction failed: ${describeThrowable(it)}", it)
    }.getOrNull()

    private fun describeThrowable(throwable: Throwable): String {
        var root = throwable
        val seen = HashSet<Throwable>()
        while (root.cause != null && root.cause !== root && seen.add(root)) {
            root = root.cause!!
        }
        val rootName = root::class.java.name
        val rootMessage = root.message?.takeIf { it.isNotBlank() } ?: "(no message)"
        return if (root === throwable) {
            "$rootName: $rootMessage"
        } else {
            "${throwable::class.java.name} -> $rootName: $rootMessage"
        }
    }
}
