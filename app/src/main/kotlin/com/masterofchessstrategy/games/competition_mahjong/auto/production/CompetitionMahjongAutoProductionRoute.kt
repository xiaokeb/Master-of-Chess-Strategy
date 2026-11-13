package com.masterofchessstrategy.games.competition_mahjong.auto.production

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoOpening
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoOpeningLibrary
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoPlayViewModel
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoRoute
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoRunConfig
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoExport
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoExportCodec
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoImportResult
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoReplayRoute
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongStorageEpochGate
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AutoHostOwner(val host: CompetitionMahjongAutoHost) : ViewModel() {
    var policy by mutableStateOf<CompetitionMahjongPlayPolicy?>(null)
    var openings = emptyList<CompetitionMahjongAutoOpening>()
    private var document: CompletableDeferred<Uri?>? = null

    fun chooseDocument(uri: Uri?) { document?.complete(uri) }

    private suspend fun pick(launch: () -> Unit): Uri? {
        check(document == null)
        val result = CompletableDeferred<Uri?>()
        document = result
        try {
            launch()
            return result.await()
        } finally {
            document = null
        }
    }

    suspend fun export(context: Context, output: CompetitionMahjongAutoExport, launch: (String) -> Unit): Boolean {
        val uri = pick { launch(output.suggestedFileName) } ?: return false
        if (!host.isCurrent()) return false
        return withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext false
            stream.use { it.write(output.bytes) }
            true
        }
    }

    suspend fun importArchive(context: Context, launch: () -> Unit): CompetitionMahjongAutoImportResult? {
        val uri = pick(launch) ?: return null
        val bytes = withContext(Dispatchers.IO) {
            val stream = checkNotNull(context.contentResolver.openInputStream(uri)) { "无法打开棋谱文件" }
            stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val limit = CompetitionMahjongAutoExportCodec.MAX_BYTES
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                    if (count < 0) break
                    require(count > 0 && output.size() + count <= limit) { "棋谱文件超出大小限制或无法读取" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        return host.store.importArchive(bytes)
    }

    override fun onCleared() { document?.cancel() }
}

/** Each route owns a single observer and shares the full-backup writer barrier. */
@Composable
internal fun CompetitionMahjongAutoProductionRoute(
    database: MocsDatabase,
    viewer: Int,
    archiveOnly: Boolean,
    writers: LocalDataWriterRegistry,
    onLearning: () -> Unit,
    onBack: () -> Unit,
) {
    val holder: AutoHostOwner = viewModel(factory = viewModelFactory {
        initializer { AutoHostOwner(CompetitionMahjongAutoHost(database, viewer)) }
    })
    val host = holder.host
    val epoch by CompetitionMahjongStorageEpochGate.forDatabase(database).epoch.collectAsState()
    val current = remember(host, epoch) { host.isCurrent() }
    var attempt by remember(host) { mutableIntStateOf(0) }
    var failure by remember(host) { mutableStateOf<String?>(null) }
    LaunchedEffect(host, attempt) {
        if (holder.policy != null || !host.isCurrent()) return@LaunchedEffect
        failure = null
        try {
            awaitLocalDataWriters(writers.snapshot().filterIsInstance<CompetitionMahjongAutoPlayViewModel>()
                .filter { it.navigationOwnerKey != host.key }) {}
            val policy = host.loadPolicy()
            if (!archiveOnly && policy.allowedBotLevels.isNotEmpty()) {
                holder.openings = withContext(Dispatchers.Default) { CompetitionMahjongAutoOpeningLibrary.load(viewer) }
            }
            holder.policy = policy
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failure = error.message ?: "无法读取自动演局资格，请重试。"
        }
    }
    val policy = holder.policy
    val firstLevel = policy?.allowedBotLevels?.firstOrNull()
    when {
        current && policy != null && archiveOnly -> {
            val context = LocalContext.current.applicationContext
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument(CompetitionMahjongAutoExport.MIME_TYPE), holder::chooseDocument,
            )
            val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), holder::chooseDocument)
            CompetitionMahjongAutoReplayRoute(host.store, viewer, policy, onBack,
                onExport = { holder.export(context, it) { name -> launcher.launch(name) } }, onWriterReady = writers::register,
                onImport = {
                    holder.importArchive(context) {
                        importer.launch(arrayOf(CompetitionMahjongAutoExport.MIME_TYPE, "application/octet-stream"))
                    }
                })
        }
        current && policy != null && firstLevel != null -> {
            val config = remember(host, firstLevel) {
                CompetitionMahjongAutoRunConfig(UUID.randomUUID().toString(),
                    System.nanoTime() and Long.MAX_VALUE, viewer, List(4) { firstLevel })
            }
            CompetitionMahjongAutoRoute(host.store, onBack, policy, initialConfig = config, openings = holder.openings, ownerKey = host.key,
                onWriterReady = writers::register, useBackgroundService = true)
        }
        else -> {
            BackHandler(onBack = onBack)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                .testTag("competition_mahjong_auto_admission"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (archiveOnly) "自动赛事棋谱" else "国标麻将 · AI自动演局", style = MaterialTheme.typography.headlineMedium)
                when {
                    !current -> Text("本地数据已替换，请返回后重新打开。")
                    failure != null -> {
                        Text(checkNotNull(failure), color = MaterialTheme.colorScheme.error)
                        Button(onClick = { attempt++ }) { Text("重试") }
                    }
                    policy == null -> CircularProgressIndicator()
                    else -> {
                        Text("完成四章国标麻将教程后开放自动演局。")
                        Button(onClick = onLearning, modifier = Modifier.testTag("competition_mahjong_auto_learning")) {
                            Text("查看教程")
                        }
                    }
                }
                TextButton(onClick = onBack) { Text("返回") }
            }
        }
    }
}
