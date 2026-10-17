package com.masterofchessstrategy.games.go

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.go.data.GoStorageEpochGate
import com.masterofchessstrategy.games.go.data.GoStoredRecord
import com.masterofchessstrategy.games.go.data.GoStoredRecordLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Archive-only entry: the replay cursor and SAF export never modify any persisted game. */
@Composable
internal fun GoStoredRecordRoute(
    recordId: String,
    database: MocsDatabase,
    gate: GoStorageEpochGate,
    onBack: () -> Unit,
) {
    key(recordId, database, gate) {
        GoStoredRecordContent(recordId, database, gate, onBack)
    }
}

@Composable
private fun GoStoredRecordContent(
    recordId: String,
    database: MocsDatabase,
    gate: GoStorageEpochGate,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val ownerEpoch = remember(recordId, database, gate) { gate.epoch.value }
    val epoch by gate.epoch.collectAsState()
    val loader = remember(database) { GoStoredRecordLoader(database) }
    val scope = rememberCoroutineScope()
    var stored by remember(recordId, database, gate) { mutableStateOf<GoStoredRecord?>(null) }
    var loading by remember(recordId, database, gate) { mutableStateOf(true) }
    var error by remember(recordId, database, gate) { mutableStateOf<String?>(null) }
    var retry by remember(recordId, database, gate) { mutableIntStateOf(0) }
    var exporting by remember(recordId, database, gate) { mutableStateOf(false) }
    var exportMessage by remember(recordId, database, gate) { mutableStateOf<String?>(null) }
    var pendingExport by remember(recordId, database, gate) { mutableStateOf<String?>(null) }

    LaunchedEffect(recordId, database, gate, ownerEpoch, retry) {
        loading = true
        error = null
        stored = null
        try {
            val loaded = gate.access(ownerEpoch) { loader.load(recordId) }
            ensureActive()
            stored = loaded
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error = failure.message ?: "无法读取围棋归档"
        } finally {
            loading = false
        }
    }

    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-go-sgf"),
    ) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null) {
            exporting = false
            exportMessage = null
        } else scope.launch {
            try {
                checkNotNull(text) { "页面已重建，请重新导出棋谱" }
                exportMessage = "正在写入 SGF 文件…"
                gate.access(ownerEpoch) {
                    withContext(Dispatchers.IO) {
                        ensureActive()
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            GoSgfDocument.write(text, output)
                        } ?: throw IOException("无法写入 SGF 文件")
                        ensureActive()
                    }
                }
                ensureActive()
                exportMessage = "SGF 棋谱已导出"
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                exportMessage = failure.message ?: "导出失败，原棋谱不受影响"
            } finally {
                exporting = false
            }
        }
    }

    val stale = epoch != ownerEpoch
    val archive = stored
    if (stale || loading || archive == null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                stale -> "本地数据已替换，请返回后重新打开棋谱"
                loading -> "正在读取围棋归档…"
                else -> error ?: "无法读取围棋归档"
            })
            if (!stale && !loading) TextButton(onClick = { retry += 1 }) { Text("重试") }
            TextButton(onClick = onBack) { Text("返回") }
        }
        return
    }

    val source = remember(archive) { GoReplaySource.Snapshot(archive.record.engineState) }
    GoReplayRoute(
        recordKey = "stored-$ownerEpoch-$recordId",
        source = source,
        onBack = onBack,
        extraControls = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when (archive.origin) {
                    GoSessionOrigin.LOCAL -> "来源：本地双人对局"
                    GoSessionOrigin.SGF_IMPORT -> "来源：SGF 导入"
                    GoSessionOrigin.LEGACY_UNKNOWN -> "来源：历史记录（未知）"
                }, modifier = Modifier.testTag("go_stored_record_origin"))
                Text("只读归档；SGF 导出完整主线，不包含死子协商等终局事件。")
                TextButton(modifier = Modifier.testTag("go_stored_record_export"), enabled = !exporting, onClick = {
                    exporting = true
                    exportMessage = "正在生成 SGF 棋谱…"
                    scope.launch {
                        var pickerLaunched = false
                        try {
                            val text = gate.access(ownerEpoch) {
                                withContext(Dispatchers.Default) {
                                    ensureActive()
                                    val sgf = GoReplaySession.openSnapshot(archive.record.engineState).use { it.exportSgf() }
                                    ensureActive()
                                    sgf
                                }
                            }
                            ensureActive()
                            pendingExport = text
                            exportMessage = null
                            exportFile.launch("go-archive.sgf")
                            pickerLaunched = true
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            pendingExport = null
                            exportMessage = failure.message ?: "无法导出 SGF 棋谱"
                        } finally {
                            if (!pickerLaunched) exporting = false
                        }
                    }
                }) { Text(if (exporting) "正在导出…" else "导出 SGF") }
                exportMessage?.let { Text(it) }
            }
        },
    )
}
