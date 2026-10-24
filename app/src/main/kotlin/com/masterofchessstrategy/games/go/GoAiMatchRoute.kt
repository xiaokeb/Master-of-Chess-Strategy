package com.masterofchessstrategy.games.go

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterofchessstrategy.engine.GoPlayer
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The shared host supplies a verified Native loader and applies the common unlock policy. */
@Composable
fun GoAiMatchRoute(
    routeKey: String,
    config: GoAiMatchConfig,
    loadEngine: suspend () -> GoAiMatchEngineOwner,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: GoAiMatchRepository? = null,
    completedSink: GoAiCompletedMatchSink? = null,
    opening: GoAiOpening? = null,
    completeHumanImmediately: Boolean = false,
    expectedMatchId: String? = null,
    onWriterReady: (GoAiMatchViewModel) -> Unit = {},
    onMatchIdentity: (String) -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val storage = remember(context, repository, expectedMatchId) {
        val source = repository ?: GoAiMatchFileRepository(context)
        if (expectedMatchId == null) source
        else GoAiExistingMatchRepository(source as? GoAiFormalMatchRepository
            ?: error("Production Go resume requires formal storage"), expectedMatchId)
    }
    val model: GoAiMatchViewModel = viewModel(key = "go-ai-$routeKey", factory = remember(storage, config, loadEngine, completedSink, opening, completeHumanImmediately) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = GoAiMatchViewModel(storage, config, loadEngine,
                completedSink = completedSink, specifiedOpening = opening,
                completeHumanImmediately = completeHumanImmediately).also {
                    it.bindNavigationOwner(routeKey)
                    onWriterReady(it)
                } as T
        }
    })
    val page by model.state.collectAsState()
    LaunchedEffect(page.match?.matchId, page.match?.game?.sessionReady) {
        page.match?.takeIf { it.game.sessionReady && it.matchId.isNotBlank() }?.let { onMatchIdentity(it.matchId) }
    }
    var actor by remember { mutableStateOf(GoPlayer.BLACK) }
    var mode by remember { mutableStateOf(GoScoreDisplayMode.STONES) }
    var pendingOpening by remember { mutableStateOf<GoAiOpening?>(null) }
    var openingMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var leaving by rememberSaveable(routeKey) { mutableStateOf(false) }
    var retirementError by remember(model) { mutableStateOf<String?>(null) }
    SideEffect { onWriterReady(model) }
    val leave: () -> Unit = {
        if (!leaving) {
            leaving = true
            model.pause()
            model.beginRetirement()
        }
    }
    // Configuration changes retain the VM. Only an explicit exit or VM.onCleared
    // retires it; a saved exit intent resumes waiting after Activity recreation.
    LaunchedEffect(model, leaving) {
        if (leaving) {
            try {
                model.beginRetirement().await()
                onBack()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                retirementError = "AI 资源释放失败，未打开其他棋局；请重启应用后继续。"
                leaving = false
            }
        }
    }
    BackHandler { leave() }
    val importOpening = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            openingMessage = "正在读取并验证指定开局…"
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(GoSgfDocument::read)
                        ?: throw IOException("无法打开 SGF 文件")
                }
                pendingOpening = withContext(Dispatchers.Default) { GoAiOpening.fromSgf(text) }
                openingMessage = null
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                pendingOpening = null
                openingMessage = error.message ?: "指定开局读取或规则验证失败"
            }
        }
    }
    // Background and returning never leave CPU search running. Auto requires explicit resume.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.pause() }
    val match = page.match
    LaunchedEffect(match?.game?.sessionReady) {
        if (match?.game?.sessionReady == true && !match.config.automatic) model.resume()
    }
    GoAiMatchScreen(page, actor, mode, { if (!leaving) model.humanAction(it) }, { actor = it },
        { mode = if (mode == GoScoreDisplayMode.STONES) GoScoreDisplayMode.POINTS else GoScoreDisplayMode.STONES },
        { model.undo() }, model::pause, model::resume, model::setSpeed, model::retryModel, model::retrySearch,
        leave, modifier, onHint = { model.requestHint() }, onRestart = { model.restart() },
        onAutomatic = { model.setAutomatic(it) },
        onAutoContinuation = { enabled, maxGames -> model.configureAutoContinuation(enabled, maxGames) },
        onRetryAutoContinuation = { model.retryAutoContinuation() },
        openingMessage = retirementError ?: if (leaving) "正在保存并释放 AI 资源…" else openingMessage,
        pendingOpening = pendingOpening, onImportOpening = {
            openingMessage = null
            importOpening.launch(arrayOf("application/x-go-sgf", "text/plain", "*/*"))
        }, onDismissOpening = { pendingOpening = null }, onConfirmOpening = {
            val candidate = pendingOpening
            if (candidate != null && model.replaceOpening(candidate)) {
                pendingOpening = null
                openingMessage = null
            } else openingMessage = "当前对局正在处理其他操作，请稍后重试。"
        }, onRetryCompletion = { model.retryCompletion() })
}
