package com.masterofchessstrategy.games.competition_mahjong.match.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistoryRoute
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongBotStrategy
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalHost
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongStatisticsRoute
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import kotlinx.coroutines.CancellationException
import java.util.UUID

private class FormalHostViewModel(val host: CompetitionMahjongFormalHost) : ViewModel() {
    var entry by mutableStateOf<CompetitionMahjongFormalHost.Entry?>(null)
    override fun onCleared() { host.close() }
}

/** The navigation entry owns the fixed lease; child destinations each own their own ViewModel. */
@Composable
internal fun CompetitionMahjongFormalRoute(
    database: MocsDatabase,
    viewer: Int,
    writers: LocalDataWriterRegistry,
    onBack: () -> Unit,
    soundEnabled: Boolean = true,
) {
    val holder: FormalHostViewModel = viewModel(factory = viewModelFactory {
        initializer { FormalHostViewModel(CompetitionMahjongFormalHost(database, viewer)) }
    })
    val host = holder.host
    var error by remember(host) { mutableStateOf<String?>(null) }
    var retry by remember(host) { mutableIntStateOf(0) }
    var confirming by remember(host) { mutableStateOf(false) }
    val ready = holder.entry as? CompetitionMahjongFormalHost.Entry.Ready
    LaunchedEffect(host, retry, confirming) {
        if (ready != null) return@LaunchedEffect
        error = null
        try {
            // A prior table can still be closing after its destination has been removed.
            awaitLocalDataWriters(writers.snapshot().filterIsInstance<CompetitionMahjongMatchPlayViewModel>()) {}
            val choice = holder.entry
            holder.entry = if (!confirming) host.open() else when (choice) {
                is CompetitionMahjongFormalHost.Entry.ConfirmMigration ->
                    CompetitionMahjongFormalHost.Entry.Ready(host.confirmMigration(choice.preview))
                is CompetitionMahjongFormalHost.Entry.ConfirmLegacyArchive ->
                    CompetitionMahjongFormalHost.Entry.Ready(host.confirmLegacyArchive(choice))
                else -> host.open()
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            holder.entry = null
            error = failure.message ?: "无法读取赛事档案，请重试"
        }
    }
    if (ready != null) {
        CompetitionMahjongFormalDestinations(host, ready.session, writers, onBack, soundEnabled)
    } else {
        BackHandler(enabled = !confirming || error != null, onBack = onBack)
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("国标麻将 · 十六盘赛事", style = MaterialTheme.typography.headlineMedium)
            if (error == null) {
                CircularProgressIndicator()
            } else {
                Text(requireNotNull(error), color = MaterialTheme.colorScheme.error)
                Button(onClick = { confirming = false; retry++ }) { Text("重试") }
            }
            OutlinedButton(onClick = onBack, enabled = !confirming || error != null) { Text("返回") }
        }
        val migration = holder.entry as? CompetitionMahjongFormalHost.Entry.ConfirmMigration
        if (migration != null && !confirming) {
            AlertDialog(
                onDismissRequest = onBack,
                title = { Text("迁移旧赛事") },
                text = { Text("旧档已完成 ${migration.preview.completedHandCount ?: 0} 盘。确认后可继续赛事，旧档仍会保留。") },
                confirmButton = { TextButton(onClick = { confirming = true }) { Text("确认迁移") } },
                dismissButton = { TextButton(onClick = onBack) { Text("取消") } },
            )
        }
        val archive = holder.entry as? CompetitionMahjongFormalHost.Entry.ConfirmLegacyArchive
        if (archive != null && !confirming) {
            AlertDialog(
                onDismissRequest = onBack,
                modifier = Modifier.testTag("competition_formal_legacy_archive"),
                title = { Text("保留旧档并新开") },
                text = {
                    Text("旧档已完成 ${archive.completedHandCount} 盘，缺少难度和辅助记录，无法继续。确认后旧档仍可随备份导出，本座位可开始新赛事。")
                },
                confirmButton = { TextButton(onClick = { confirming = true }) { Text("保留旧档") } },
                dismissButton = { TextButton(onClick = onBack) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun CompetitionMahjongFormalDestinations(
    host: CompetitionMahjongFormalHost,
    session: CompetitionMahjongFormalHost.Session,
    writers: LocalDataWriterRegistry,
    onBack: () -> Unit,
    soundEnabled: Boolean,
) {
    val navigator = rememberNavController()
    val viewer = host.viewerPlayerId
    val policy by session.policyUpdates.collectAsState()
    val easyLevel = policy.allowedBotLevels.firstOrNull()
    NavHost(navigator, startDestination = "summary") {
        composable("summary") {
            BackHandler(onBack = onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                    .testTag("competition_formal_summary"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("国标麻将 · 十六盘赛事", style = MaterialTheme.typography.headlineMedium)
                Text("初始${"东南西北"[viewer]}位")
                if (easyLevel == null) Text("完成四章独立教程后解锁简单难度。")
                Button(enabled = easyLevel != null, onClick = { navigator.navigate("match") },
                    modifier = Modifier.testTag("competition_formal_play")) { Text("开始或继续赛事") }
                OutlinedButton(onClick = { navigator.navigate("statistics") },
                    modifier = Modifier.testTag("competition_formal_statistics")) { Text("赛事统计") }
                OutlinedButton(onClick = { navigator.navigate("history") },
                    modifier = Modifier.testTag("competition_formal_history")) { Text("赛事历史") }
                OutlinedButton(onClick = onBack) { Text("返回") }
            }
        }
        composable("match") {
            if (easyLevel != null) {
                val config = remember {
                    CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(),
                        System.currentTimeMillis().coerceAtLeast(0), viewer, easyLevel,
                        CompetitionMahjongBotStrategy.POSITION_SEEDED_V1)
                }
                CompetitionMahjongMatchRoute(
                    store = session.match, initialConfig = config, playPolicy = session.policy,
                    policyProvider = { session.policy },
                    ownerKey = host.ownerKey, ownerIsCurrent = host::isCurrent, writers = writers,
                    onBack = { navigator.popBackStack() },
                    soundEnabled = soundEnabled,
                )
            } else {
                LaunchedEffect(Unit) { navigator.popBackStack() }
            }
        }
        composable("statistics") {
            CompetitionMahjongStatisticsRoute(viewer, { navigator.popBackStack() }, store = session.statistics)
        }
        composable("history") {
            CompetitionMahjongMatchHistoryRoute(viewer, { navigator.popBackStack() }, store = session.history, writers = writers)
        }
    }
}
