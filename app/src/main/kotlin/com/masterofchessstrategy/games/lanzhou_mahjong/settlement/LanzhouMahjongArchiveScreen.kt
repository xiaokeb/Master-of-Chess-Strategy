package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.LanzhouMahjongMeldKind
import com.masterofchessstrategy.games.lanzhou_mahjong.difficultyName
import com.masterofchessstrategy.games.lanzhou_mahjong.tileName
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException

@Composable
internal fun LanzhouMahjongArchiveScreen(archive: LanzhouMahjongArchive, onBack: () -> Unit) {
    val epoch by archive.epoch.collectAsState()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var retry by rememberSaveable { mutableIntStateOf(0) }
    val back = {
        if (selected == null || archive.ownerEpoch != epoch) onBack() else selected = null
    }
    BackHandler(onBack = back)
    val scroll = key(selected, page) { rememberScrollState() }
    Surface(Modifier.fillMaxSize().testTag("lanzhou_record_browser")) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(back, modifier = Modifier.testTag("lanzhou_record_back")) { Text("返回") }
            val id = selected
            if (archive.ownerEpoch != epoch) {
                Text("本地数据已更换，请返回后重新打开棋谱")
            } else if (id == null) {
                Text("兰州麻将正式棋谱", style = MaterialTheme.typography.titleLarge)
                val result = archiveRequest(Triple(null, page, retry)) { archive.page(page) }
                when {
                    result == null -> Text("正在读取棋谱…")
                    result.isFailure -> ArchiveReadError { retry++ }
                    else -> {
                        val loaded = result.getOrThrow()
                        if (loaded.items.isEmpty()) Text("完成正式对局后，棋谱会显示在这里。")
                        loaded.items.forEach { item ->
                            OutlinedButton(
                                { step = 0; selected = item.id },
                                modifier = Modifier.fillMaxWidth().testTag("lanzhou_record_${item.id}"),
                            ) {
                                val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                    .format(Date(item.capturedAt))
                                Text("${difficultyName(item.difficulty)} · ${outcomeName(item.outcome)} · $time")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ page-- }, enabled = page > 0) { Text("上一页") }
                            OutlinedButton({ page++ }, enabled = loaded.hasNext) { Text("下一页") }
                        }
                    }
                }
            } else {
                Text("棋谱回放", style = MaterialTheme.typography.titleLarge)
                val result = archiveRequest(Triple(id, step, retry)) { archive.frame(id, step) }
                when {
                    result == null -> Text("正在恢复棋谱步骤…")
                    result.isFailure -> ArchiveReadError { retry++ }
                    else -> ReplayFrame(result.getOrThrow()) { step = it }
                }
            }
        }
    }
}

@Composable
private fun <T> archiveRequest(request: Any, load: suspend () -> T): Result<T>? = key(request) {
    produceState<Result<T>?>(null) {
        value = try {
            Result.success(load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        } catch (failure: LinkageError) {
            Result.failure(failure)
        }
    }.value
}

@Composable
private fun ArchiveReadError(retry: () -> Unit) {
    Text("棋谱读取失败，原记录已保留")
    OutlinedButton(retry) { Text("重新读取") }
}

@Composable
private fun ReplayFrame(frame: LanzhouMahjongReplayFrame, goTo: (Int) -> Unit) {
    Text("${frame.index} / ${frame.actionCount} 步", Modifier.testTag("lanzhou_record_step"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ goTo(frame.index - 1) }, enabled = frame.index > 0,
            modifier = Modifier.testTag("lanzhou_record_previous")) { Text("上一步") }
        OutlinedButton({ goTo(frame.index + 1) }, enabled = frame.index < frame.actionCount,
            modifier = Modifier.testTag("lanzhou_record_next")) { Text("下一步") }
        OutlinedButton({ goTo(frame.actionCount) }, enabled = frame.index < frame.actionCount) { Text("终局") }
    }
    val move = frame.move
    Text(if (move == null) "初始牌局" else
        "${move.seat + 1} 号席${actionName(move.kind)}${move.tile?.let { " ${tileName(it)}" }.orEmpty()}")
    Text("庄家 ${frame.view.dealerSeat + 1} 号席 · 剩余 ${frame.view.wallRemaining} 张")
    if (frame.index == frame.actionCount) {
        val winner = frame.view.outcome.winnerSeat
        Text(when {
            frame.view.outcome.falseWin -> "终局：炸胡"
            winner != null -> "终局：${winner + 1} 号席胡牌"
            else -> "终局：黄庄"
        })
    }
    frame.view.seats.forEach { seat ->
        val seatLabel = if (seat.seat == frame.viewerSeat) "（本机）" else ""
        Text("${seat.seat + 1} 号席$seatLabel · 手牌 ${seat.concealedCount} 张 · 净分 ${frame.view.netScores[seat.seat]}")
        if (seat.seat == frame.viewerSeat && seat.concealedTiles.isNotEmpty()) {
            Text("手牌：${seat.concealedTiles.sorted().joinToString(" ", transform = ::tileName)}")
        }
        if (seat.discards.isNotEmpty()) Text("弃牌：${seat.discards.joinToString(" ", transform = ::tileName)}")
        if (seat.melds.isNotEmpty()) Text("副露：${seat.melds.joinToString(" · ") { meld ->
            val name = when (meld.kind) {
                LanzhouMahjongMeldKind.PUNG -> "碰"
                LanzhouMahjongMeldKind.EXPOSED_KONG -> "明杠"
                LanzhouMahjongMeldKind.CONCEALED_KONG -> "暗杠"
            }
            if (meld.isTileHidden) name else "$name ${tileName(meld.tile)}"
        }}")
    }
}

private fun outcomeName(outcome: LanzhouMahjongQualifiedOutcome): String = when (outcome) {
    LanzhouMahjongQualifiedOutcome.WIN -> "本机胡牌"
    LanzhouMahjongQualifiedOutcome.LOSS -> "其他席胡牌"
    LanzhouMahjongQualifiedOutcome.EXHAUSTIVE_DRAW -> "黄庄"
    LanzhouMahjongQualifiedOutcome.FALSE_DECLARATION -> "炸胡"
}

private fun actionName(kind: LanzhouMahjongActionKind): String = when (kind) {
    LanzhouMahjongActionKind.DRAW -> "摸牌"
    LanzhouMahjongActionKind.DISCARD -> "打牌"
    LanzhouMahjongActionKind.READY_DISCARD -> "报听打牌"
    LanzhouMahjongActionKind.PASS -> "过"
    LanzhouMahjongActionKind.PUNG -> "碰"
    LanzhouMahjongActionKind.EXPOSED_KONG -> "明杠"
    LanzhouMahjongActionKind.CONCEALED_KONG -> "暗杠"
    LanzhouMahjongActionKind.ADDED_KONG -> "加杠"
    LanzhouMahjongActionKind.WIN -> "声明胡牌"
}
