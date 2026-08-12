/*
 * FMZlinkR disclaimer UI.
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val SHIZU_REPO = "https://github.com/kitsumed/ShizuCallRecorder"
private const val CALLVAULT_REPO = "https://github.com/madkongo/CallVault"

@Composable
fun FmzDisclaimerScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    var accepted by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("FMZlinkR", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "FMZlinkRはShizuCallRecorderのFORK（改変版）であり、元プロジェクトとは別のアプリです。",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "派生元はkitsumed/ShizuCallRecorderです。VoIP録音部分ではmadkongo/CallVaultのAudioPolicy・USAGE_VOICE_COMMUNICATION・MICミックス等の実装を参考・適用しています。著作権表示とライセンスはLICENSE/NOTICEに従います。",
            )
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZU_REPO))) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("派生元 ShizuCallRecorder を開く")
            }
            OutlinedButton(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CALLVAULT_REPO))) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("VoIP参考元 CallVault を開く")
            }
            Text(
                "FMZlinkRはShizukuを利用しますが、Shizuku server、adbd、USBデバッグ、ワイヤレスデバッグをアプリから起動・停止・再起動しません。",
            )
            Text(
                "通話録音の適法性や必要な同意は地域・状況により異なります。録音を行う前に、ご自身の利用環境で必要な同意・告知を確認してください。",
            )
            Text(
                "本ソフトウェアはGNU GPL v3 or later（適用されるSection 7追加条項を含む）の条件で提供され、無保証です。",
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = accepted,
                        onValueChange = { accepted = it },
                        role = Role.Checkbox,
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = accepted, onCheckedChange = null)
                Text("内容を確認し、自分の責任で利用します。", modifier = Modifier.padding(start = 8.dp))
            }
            Button(onClick = onContinue, enabled = accepted, modifier = Modifier.fillMaxWidth()) {
                Text("続行")
            }
        }
    }
}
