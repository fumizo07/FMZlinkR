/*
 * FMZlinkR floating recording control.
 * Visual concept derived from ShizuCallRecorder's RecordingOverlay.
 * Copyright (C) 2026-present kitsumed (Med)
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fumizo07.fmzlinkr.R

@Composable
fun FmzRecordingOverlay(
    isRecording: Boolean,
    onActionClick: () -> Unit,
    onDragY: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .height(IntrinsicSize.Max)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                    ) { change, dragAmount ->
                        change.consume()
                        onDragY(dragAmount.y)
                    }
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = onActionClick,
                    modifier = Modifier
                        .size(64.dp)
                        .border(
                            width = 4.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            shape = CircleShape,
                        )
                        .background(
                            color = if (isRecording) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        painter = painterResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_mic),
                        contentDescription = if (isRecording) "録音停止" else "録音開始",
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            Box(
                modifier = Modifier.fillMaxHeight().width(26.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_outline_drag_indicator),
                    contentDescription = "移動",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
    }
}
