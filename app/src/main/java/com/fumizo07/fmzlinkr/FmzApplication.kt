/*
 * FMZlinkR
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr

import android.app.Application
import com.fumizo07.fmzlinkr.utils.AppLogger

class FmzApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(applicationContext)
    }
}
