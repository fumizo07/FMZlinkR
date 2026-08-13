/*
 * FMZlinkR overlay host.
 * Hosting approach adapted from ShizuCallRecorder's RecordingOverlayController.
 * Copyright (C) 2026-present kitsumed (Med)
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.services.recording

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.fumizo07.fmzlinkr.data.FmzPreferences
import com.fumizo07.fmzlinkr.ui.common.FmzRecordingOverlay
import com.fumizo07.fmzlinkr.ui.theme.FMZlinkRTheme
import com.fumizo07.fmzlinkr.utils.AppLogger
import kotlin.math.max
import kotlin.math.min

class FmzOverlayController(private val context: Context) {
    private val prefs = FmzPreferences(context)
    private val overlayContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else context
    }
    private val windowManager by lazy { overlayContext.getSystemService(WindowManager::class.java) }
    private var composeView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var owner: ComposeWindowLifecycleOwner? = null
    private var savedY = -1

    fun show(isRecording: Boolean, onActionClick: () -> Unit) {
        if (!prefs.isOverlayEnabled() || !Settings.canDrawOverlays(context)) {
            if (composeView != null) {
                AppLogger.i("FMZlinkR overlay hidden because overlay setting/permission is unavailable")
            }
            hide()
            return
        }
        if (composeView == null) {
            initView()
            AppLogger.i("FMZlinkR overlay shown isRecording=$isRecording")
        }
        composeView?.setContent {
            FMZlinkRTheme(
                darkTheme = isSystemInDarkTheme(),
                dynamicColor = true,
            ) {
                FmzRecordingOverlay(
                    isRecording = isRecording,
                    onActionClick = {
                        AppLogger.i("FMZlinkR overlay action clicked isRecording=$isRecording")
                        onActionClick()
                    },
                    onDragY = ::moveY,
                    onDragEnd = ::saveY,
                )
            }
        }
    }

    private fun initView() {
        val screenHeight = windowManager.currentWindowMetrics.bounds.height()
        val overlayHeight = (120 * overlayContext.resources.displayMetrics.density).toInt()
        val y = if (savedY < 0) max(0, (screenHeight - overlayHeight) / 2) else savedY
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            this.y = y
        }
        composeView = ComposeView(overlayContext).also { view ->
            owner = ComposeWindowLifecycleOwner().apply { attach(view) }
            windowManager.addView(view, params)
        }
    }

    private fun moveY(delta: Float) {
        val p = params ?: return
        val view = composeView ?: return
        val height = windowManager.currentWindowMetrics.bounds.height()
        p.y = min(height - 250, max(100, p.y + delta.toInt()))
        windowManager.updateViewLayout(view, p)
    }

    private fun saveY() {
        savedY = params?.y ?: savedY
    }

    fun hide() {
        val wasVisible = composeView != null
        composeView?.let { runCatching { windowManager.removeView(it) } }
        owner?.destroy()
        owner = null
        composeView = null
        params = null
        if (wasVisible) AppLogger.i("FMZlinkR overlay hidden")
    }

    private class ComposeWindowLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store

        init {
            savedStateController.performRestore(Bundle())
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun attach(view: View) {
            view.setViewTreeLifecycleOwner(this)
            view.setViewTreeViewModelStoreOwner(this)
            view.setViewTreeSavedStateRegistryOwner(this)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }
}
