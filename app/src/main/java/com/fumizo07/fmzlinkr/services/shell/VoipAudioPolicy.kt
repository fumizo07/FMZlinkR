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
            // Null Context is intentional and matches the proven CallVault audio-policy path.
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
            val record = ShellAudioAttribution.withoutClientApplication("far-party sink") {
                policyClass.getMethod("createAudioRecordSink", mixClass)
                    .invoke(currentPolicy, currentMix) as AudioRecord?
            }
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                val state = record?.state
                runCatching { record?.release() }
                AppLogger.w("VoIP far-party sink failed to initialise with shell attribution (state=$state)")
                null
            } else {
                AppLogger.i("VoIP far-party sink initialised successfully with shell attribution")
                record
            }
        }.onFailure {
            AppLogger.e("VoIP far-party sink creation failed: ${describeThrowable(it)}", it)
        }.getOrNull()
    }

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
