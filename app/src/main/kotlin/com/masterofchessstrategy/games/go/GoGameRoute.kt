package com.masterofchessstrategy.games.go

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Game-owned route; the shared navigator can call this after its serialized integration window. */
@Composable
fun GoGameRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: GoSessionRepository? = null,
    viewModelKey: String = "go-local-game",
    onWriterReady: (ViewModel) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val sessions = remember(context, repository) { repository ?: GoSessionFileRepository(context) }
    val game: GoGameViewModel = viewModel(key = viewModelKey, factory = remember(sessions, onWriterReady) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GoGameViewModel(sessions).also(onWriterReady) as T
        }
    })
    onWriterReady(game) // Retained owners must also be registered after activity recreation.
    val state by game.state.collectAsState()
    var showSetup by remember { mutableStateOf(false) }
    var showImportConfirmation by remember { mutableStateOf(false) }
    var recordMessage by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            recordMessage = "正在读取 SGF 文件…"
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(GoSgfDocument::read)
                        ?: throw IOException("无法打开 SGF 文件")
                }
                recordMessage = null
                game.importSgf(text)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                recordMessage = error.message ?: "读取 SGF 文件失败"
            }
        }
    }
    val exportFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-go-sgf"),
    ) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri != null) scope.launch {
            recordMessage = "正在写入 SGF 文件…"
            try {
                checkNotNull(text) { "导出页面已重建，请重新导出棋谱" }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { GoSgfDocument.write(text, it) }
                        ?: throw IOException("无法写入 SGF 文件")
                }
                recordMessage = "SGF 棋谱已导出"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                recordMessage = error.message ?: "写入 SGF 文件失败"
            }
        }
    }
    GoGameScreen(
        state = state,
        onIntersectionTap = game::play,
        onPass = game::pass,
        onUndo = game::undo,
        onConfirmScore = game::confirm,
        onResumePlay = game::resume,
        onScoringActorSelect = game::selectScoringActor,
        onScoreModeToggle = game::toggleScoreMode,
        onLegalPointsToggle = game::toggleLegalPoints,
        onRetrySave = game::retrySave,
        onNewGame = { showSetup = true },
        onBack = onBack,
        modifier = modifier,
        onImportSgf = { showImportConfirmation = true },
        onExportSgf = {
            scope.launch {
                try {
                    pendingExport = game.exportSgf()
                    recordMessage = null
                    exportFile.launch("go-${state.boardSize}.sgf")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    recordMessage = error.message ?: "无法导出 SGF 棋谱"
                }
            }
        },
        recordMessage = recordMessage,
    )
    if (showSetup) {
        GoMatchSetupDialog(
            onDismiss = { showSetup = false },
            onStart = { size, handicap ->
                showSetup = false
                game.newGame(size, handicap)
            },
        )
    }
    if (showImportConfirmation) {
        AlertDialog(
            onDismissRequest = { showImportConfirmation = false },
            title = { Text("导入 SGF 棋谱") },
            text = { Text("导入成功会替换当前对局。请先导出需要保留的主线；SGF 不保留死子协商和完整终局。") },
            confirmButton = {
                TextButton(onClick = {
                    showImportConfirmation = false
                    recordMessage = null
                    importFile.launch(arrayOf("*/*"))
                }) { Text("选择文件") }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirmation = false }) { Text("取消") }
            },
        )
    }
}

@Composable
fun GoMatchSetupDialog(onDismiss: () -> Unit, onStart: (Int, Int) -> Unit) {
    var size by remember { mutableIntStateOf(19) }
    var handicap by remember { mutableIntStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新围棋对局") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("棋盘路数")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (option in listOf(9, 13, 19)) {
                        FilterChip(selected = size == option, onClick = { size = option },
                            label = { Text("${option}路") })
                    }
                }
                Text("固定星位让子；让子局白棋先行、黑棋不贴目")
                for (options in listOf(listOf(0, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (option in options) {
                            FilterChip(selected = handicap == option,
                                onClick = { handicap = option },
                                label = { Text(if (option == 0) "不让子" else "$option 子") })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(size, handicap) }) { Text("开始") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
