/*
 * FMZlinkR Shizuku connection manager.
 * Derived from ShizuCallRecorder's Shizuku UserService binding implementation.
 * Copyright (C) 2026-present kitsumed (Med)
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.integrations.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.fumizo07.fmzlinkr.BuildConfig
import com.fumizo07.fmzlinkr.IShellService
import com.fumizo07.fmzlinkr.services.shell.ShellService
import com.fumizo07.fmzlinkr.utils.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Binds only FMZlinkR's own Shizuku UserService.
 * This class never starts, stops, restarts, or reconfigures the Shizuku server or adbd.
 */
class ShizukuConnectionManager(
    private val context: Context,
    private val onBinderDied: () -> Unit = {},
) {
    companion object {
        private const val PERMISSION_REQUEST_CODE = 204846

        fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }
            .onFailure { AppLogger.d("Shizuku unavailable: ${it.message}") }
            .getOrDefault(false)

        fun hasPermission(context: Context? = null): Boolean = try {
            if (isAvailable()) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                context?.checkSelfPermission(ShizukuProvider.PERMISSION) == PackageManager.PERMISSION_GRANTED
            }
        } catch (e: Exception) {
            AppLogger.w("Could not check Shizuku permission: ${e.message}", e)
            false
        }

        /** Opens Shizuku's normal user permission dialog. It does not start Shizuku. */
        fun requestPermission() {
            if (isAvailable() && !hasPermission()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        }
    }

    private val appContext = context.applicationContext

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        val version = appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .longVersionCode
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        Shizuku.UserServiceArgs(
            ComponentName(appContext.packageName, ShellService::class.java.name),
        )
            // UserService lifetime follows this app process/FGS; it is not an independent daemon.
            .daemon(false)
            .processNameSuffix("FMZlinkRShellService")
            .debuggable(BuildConfig.DEBUG)
            .version(version)
    }

    @Volatile private var serviceConnection: ServiceConnection? = null

    suspend fun getShellService(): IShellService = suspendCancellableCoroutine { continuation ->
        if (!isAvailable()) {
            continuation.resumeWithException(IllegalStateException("Shizuku is not running"))
            return@suspendCancellableCoroutine
        }
        if (!hasPermission(appContext)) {
            continuation.resumeWithException(SecurityException("Shizuku permission has not been granted"))
            return@suspendCancellableCoroutine
        }
        if (serviceConnection != null) {
            continuation.resumeWithException(IllegalStateException("Shizuku UserService bind is already in progress or connected"))
            return@suspendCancellableCoroutine
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    serviceConnection = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("Shizuku returned a null UserService binder"))
                    }
                    return
                }
                val proxy = IShellService.Stub.asInterface(binder)
                runCatching { proxy.setLogCallback(AppLogger.callback, true) }
                    .onFailure { AppLogger.w("Could not attach UserService logger: ${it.message}") }
                AppLogger.i("FMZlinkR Shizuku UserService connected")
                if (continuation.isActive) continuation.resume(proxy)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceConnection = null
                AppLogger.w("FMZlinkR Shizuku UserService disconnected")
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Shizuku UserService disconnected during binding"))
                } else {
                    onBinderDied()
                }
            }
        }
        serviceConnection = connection

        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Exception) {
            serviceConnection = null
            continuation.resumeWithException(e)
            return@suspendCancellableCoroutine
        }

        continuation.invokeOnCancellation {
            if (serviceConnection === connection) {
                runCatching {
                    if (isAvailable()) Shizuku.unbindUserService(userServiceArgs, connection, false)
                }
                serviceConnection = null
            }
        }
    }

    /** Unbinds only FMZlinkR's UserService; the Shizuku server remains untouched. */
    fun unbind() {
        val connection = serviceConnection ?: return
        serviceConnection = null
        runCatching {
            if (isAvailable()) {
                Shizuku.unbindUserService(userServiceArgs, connection, false)
                AppLogger.i("FMZlinkR Shizuku UserService unbound")
            }
        }.onFailure {
            AppLogger.w("FMZlinkR UserService unbind failed: ${it.message}", it)
        }
    }
}
