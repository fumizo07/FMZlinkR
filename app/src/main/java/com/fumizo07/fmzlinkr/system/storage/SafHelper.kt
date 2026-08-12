/*
 * FMZlinkR storage helper, derived from ShizuCallRecorder SafHelper.
 * Copyright (C) 2026-present kitsumed (Med)
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.system.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

object SafHelper {
    data class SafResult(
        val uri: Uri,
        val descriptor: ParcelFileDescriptor,
        val displayName: String,
    )

    fun createAudioFile(context: Context, folderUri: Uri, filePath: String, mimeType: String): SafResult? {
        val rootDir = DocumentFile.fromTreeUri(context, folderUri) ?: return null
        if (!rootDir.canWrite()) return null
        var currentDir = rootDir
        val segments = filePath.split("/")
        val fileName = segments.last()
        for (dirName in segments.dropLast(1)) {
            if (dirName.isBlank()) continue
            val existing = currentDir.findFile(dirName)
            currentDir = if (existing != null && existing.isDirectory) {
                existing
            } else {
                currentDir.createDirectory(dirName) ?: return null
            }
        }
        val newFile = currentDir.createFile(mimeType, fileName) ?: return null
        val descriptor = context.contentResolver.openFileDescriptor(newFile.uri, "rw") ?: return null
        return SafResult(
            uri = newFile.uri,
            descriptor = descriptor,
            displayName = "${rootDir.name}/${filePath.trimStart('/')}",
        )
    }

    @OptIn(ExperimentalContracts::class)
    fun isFolderValid(context: Context, folderUri: Uri?): Boolean {
        contract { returns(true) implies (folderUri != null) }
        if (folderUri == null) return false
        val directory = DocumentFile.fromTreeUri(context, folderUri)
        return directory != null && directory.exists() && directory.canWrite()
    }

    fun getFolderDisplayNameOrNull(context: Context, folderUri: Uri?): String? {
        if (folderUri == null) return null
        return DocumentFile.fromTreeUri(context, folderUri)?.name
    }
}
