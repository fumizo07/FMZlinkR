/*
 * FMZlinkR
 * Adapted from CallVault PcmDownmix.
 * Copyright (C) 2026-present The CallVault Authors
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.utils

internal object PcmDownmix {
    fun stereoToMono(src: ByteArray, srcLen: Int, dst: ByteArray): Int {
        var di = 0
        var si = 0
        while (si + 3 < srcLen) {
            val left = (src[si].toInt() and 0xFF or (src[si + 1].toInt() shl 8)).toShort().toInt()
            val right = (src[si + 2].toInt() and 0xFF or (src[si + 3].toInt() shl 8)).toShort().toInt()
            val mono = (left + right) / 2
            dst[di] = (mono and 0xFF).toByte()
            dst[di + 1] = ((mono shr 8) and 0xFF).toByte()
            di += 2
            si += 4
        }
        return di
    }
}
