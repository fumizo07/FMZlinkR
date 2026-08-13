/*
 * FMZlinkR focused settings UI.
 * Copyright (C) 2026 fumizo07
 * Licensed under GNU GPL v3 or later with applicable Section 7 terms.
 */
package com.fumizo07.fmzlinkr.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fumizo07.fmzlinkr.data.FmzPreferences
import com.fumizo07.fmzlinkr.integrations.shizuku.ShizukuConnectionManager
import com.fumizo07.fmzlinkr.services.recording.RakutenLinkRecordingService
import com.fumizo07.fmzlinkr.system.storage.SafHelper
import com.fumizo07.fmzlinkr.utils.AppLogger

private const val UPSTREAM_REPO_URL = "https://github.com/kitsumed/ShizuCallRecorder"
private const val CALLVAULT_REPO_URL = "https://github.com/madkongo/CallVault"

// The diagnostics implementation remains available for future device investigations, but is hidden
// during normal use. AppLogger's in-app diagnostic buffer is disabled separately as well.
private const val SHOW_RUNTIME_DIAGNOSTICS = false

@Composable
fun FmzSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { FmzPreferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    var fileNameTemplate by remember { mutableStateOf(prefs.getFileNameTemplate()) }
    revision

    val folderUri = prefs.getRecordingFolderUri()
    val folderValid = SafHelper.isFolderValid(context, folderUri)
    val folderName = SafHelper.getFolderDisplayNameOrNull(context, folderUri)
    val shizukuRunning = ShizukuConnectionManager.isAvailable()
    val shizukuPermission = ShizukuConnectionManager.hasPermission(context)
    val overlayPermission = Settings.canDrawOverlays(context)
    val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val rakutenInstalled = runCatching {
        context.packageManager.getApplicationInfo("jp.co.rakuten.mobile.rcs", 0)
    }.isSuccess

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            prefs.setRecordingFolderUri(uri)
            revision++
        }
    }
    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        revision++
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        revision++
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "FMZlinkR",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "Rakuten LinkのVoIP通話をShizuku UserServiceで録音します。FMZlinkRはShizuku・adbd・USB/ワイヤレスデバッグを起動・停止しません。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("セットアップ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    StatusRow("Rakuten Link", rakutenInstalled, if (rakutenInstalled) "インストール済み" else "見つかりません")
                    StatusRow("Shizuku", shizukuRunning, if (shizukuRunning) "起動中" else "Shizuku側で起動してください")
                    StatusRow("Shizuku権限", shizukuPermission, if (shizukuPermission) "許可済み" else "未許可")
                    if (shizukuRunning && !shizukuPermission) {
                        Button(onClick = { ShizukuConnectionManager.requestPermission() }) {
                            Text("Shizuku権限を許可")
                        }
                    }
                    StatusRow("録音保存先", folderValid, if (folderValid) (folderName ?: "選択済み") else "未選択")
                    Button(onClick = { folderLauncher.launch(folderUri) }) {
                        Text("録音フォルダを選択")
                    }
                    StatusRow("通知", notificationsGranted, if (notificationsGranted) "許可済み" else "未許可")
                    if (!notificationsGranted && Build.VERSION.SDK_INT >= 33) {
                        Button(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text("通知を許可")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Rakuten Link録音", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    SwitchRow(
                        title = "録音待機",
                        description = "通話が始まる前からAudioPolicyを待機させます。録音するにはこの待機が必要です。",
                        checked = prefs.isMonitoringEnabled(),
                        enabled = shizukuRunning && shizukuPermission && folderValid && rakutenInstalled,
                        onCheckedChange = { enabled ->
                            prefs.setMonitoringEnabled(enabled)
                            if (enabled) RakutenLinkRecordingService.enable(context)
                            else RakutenLinkRecordingService.disable(context)
                            revision++
                        },
                    )

                    HorizontalDivider()

                    SwitchRow(
                        title = "Rakuten Linkのみ自動録音",
                        description = "Rakuten Linkの音声所有UIDを確認できた通話だけ自動録音します。判定不能なVoIPは自動録音しません。",
                        checked = prefs.isAutoRecordEnabled(),
                        enabled = true,
                        onCheckedChange = {
                            prefs.setAutoRecordEnabled(it)
                            RakutenLinkRecordingService.refreshSettings(context)
                            revision++
                        },
                    )

                    HorizontalDivider()

                    SwitchRow(
                        title = "通話中に録音ボタンを表示",
                        description = "通話中にフローティングの録音開始/停止ボタンを表示します。通知からも手動録音できます。",
                        checked = prefs.isOverlayEnabled(),
                        enabled = true,
                        onCheckedChange = { enabled ->
                            prefs.setOverlayEnabled(enabled)
                            if (enabled && !Settings.canDrawOverlays(context)) {
                                overlayLauncher.launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                            RakutenLinkRecordingService.refreshSettings(context)
                            revision++
                        },
                    )
                    if (prefs.isOverlayEnabled() && !overlayPermission) {
                        Text(
                            "オーバーレイ権限が未許可です。通知の『録音開始』は権限なしでも利用できます。",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = {
                            overlayLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                            )
                        }) {
                            Text("オーバーレイ権限を開く")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("録音形式・ファイル名", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Text("ファイル拡張子", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ChoiceButton(
                            selected = prefs.getAudioCodec() == FmzPreferences.AUDIO_CODEC_OPUS,
                            label = "OGG (Opus)",
                            onClick = {
                                prefs.setAudioCodec(FmzPreferences.AUDIO_CODEC_OPUS)
                                revision++
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ChoiceButton(
                            selected = prefs.getAudioCodec() == FmzPreferences.AUDIO_CODEC_AAC,
                            label = "M4A (AAC)",
                            onClick = {
                                prefs.setAudioCodec(FmzPreferences.AUDIO_CODEC_AAC)
                                revision++
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    HorizontalDivider()

                    Text("音質（ビットレート）", fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(8_000, 16_000, 32_000).forEach { bitRate ->
                            ChoiceButton(
                                selected = prefs.getAudioBitRate() == bitRate,
                                label = "${bitRate / 1000}k",
                                onClick = {
                                    prefs.setAudioBitRate(bitRate)
                                    revision++
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(64_000, 128_000).forEach { bitRate ->
                            ChoiceButton(
                                selected = prefs.getAudioBitRate() == bitRate,
                                label = "${bitRate / 1000}k",
                                onClick = {
                                    prefs.setAudioBitRate(bitRate)
                                    revision++
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    Text(
                        "通話録音は16kbps Opusを初期値にしています。AACでは32kbps以上を推奨します。",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    HorizontalDivider()

                    Text("ファイル名を修正", fontWeight = FontWeight.Medium)
                    OutlinedTextField(
                        value = fileNameTemplate,
                        onValueChange = {
                            fileNameTemplate = it
                            prefs.setFileNameTemplate(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("ファイル名形式") },
                        supportingText = {
                            Text("{date} / {direction} / {app} が使用できます。拡張子は自動で付きます。")
                        },
                    )
                    Text(
                        "初期値: {date}_{direction}_{app}　発信=in / 着信=out",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        fileNameTemplate = FmzPreferences.DEFAULT_FILE_NAME_TEMPLATE
                        prefs.setFileNameTemplate(fileNameTemplate)
                    }) {
                        Text("ファイル名形式を初期値に戻す")
                    }
                }
            }

            if (SHOW_RUNTIME_DIAGNOSTICS) {
                RuntimeDiagnosticsCard(prefs = prefs, onRefresh = { revision++ })
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("使い方", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("1. Shizukuをユーザー自身で起動します。")
                    Text("2. FMZlinkRで『録音待機』をONにします。")
                    Text("3. その後にRakuten Link通話を開始します。")
                    Text("4. 自動録音OFFなら、通話中のフローティングボタンまたは通知から好きなタイミングで録音開始/停止できます。")
                    Spacer(Modifier.height(4.dp))
                    Text("重要: 通話が始まってから録音待機をONにした場合、その通話にはAudioPolicyが間に合わないため、次の通話から有効です。")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("派生元・ライセンス", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "FMZlinkRはShizuCallRecorderのFORK（改変版）であり、元プロジェクトとは別のアプリです。VoIP録音部分ではCallVaultの実装を参考・適用しています。",
                    )
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UPSTREAM_REPO_URL))) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("派生元 ShizuCallRecorder を開く")
                    }
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CALLVAULT_REPO_URL))) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("VoIP参考元 CallVault を開く")
                    }
                    Text("ライセンス: GNU GPL v3 or later + 適用されるSection 7追加条項", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ChoiceButton(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun RuntimeDiagnosticsCard(prefs: FmzPreferences, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val audioMode = context.getSystemService(AudioManager::class.java).mode
    val diagnosticText = AppLogger.diagnosticSnapshot()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("実機診断", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "AudioManager mode: $audioMode (${audioModeLabel(audioMode)}) / 録音待機設定: ${if (prefs.isMonitoringEnabled()) "ON" else "OFF"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefresh) { Text("更新") }
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("FMZlinkR diagnostics", AppLogger.diagnosticSnapshot()),
                    )
                    onRefresh()
                }) {
                    Text("ログをコピー")
                }
                OutlinedButton(onClick = {
                    AppLogger.clearDiagnostics()
                    onRefresh()
                }) {
                    Text("消去")
                }
            }
            SelectionContainer {
                Text(
                    diagnosticText.ifBlank { "診断ログは現在無効です。" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun audioModeLabel(mode: Int): String = when (mode) {
    AudioManager.MODE_NORMAL -> "NORMAL"
    AudioManager.MODE_RINGTONE -> "RINGTONE"
    AudioManager.MODE_IN_CALL -> "IN_CALL"
    AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
    AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
    else -> "UNKNOWN"
}

@Composable
private fun StatusRow(label: String, ok: Boolean, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Text(
            if (ok) "✓ $value" else "× $value",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
