package com.masterofchessstrategy.games.competition_mahjong

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal const val COMPETITION_MAHJONG_TABLE_TAG = "competition_mahjong_table"
internal const val COMPETITION_MAHJONG_RESULT_TAG = "competition_mahjong_result"

/** The local hand stays private; the winner's hand is separately revealed in the result. */
@Composable
internal fun CompetitionMahjongTableScreen(
    state: CompetitionMahjongTableUiState,
    onAction: (String) -> Unit,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleAutoPlay: () -> Unit = {},
    onAutoPlaySpeedChanged: (Float) -> Unit = {},
    onUndo: () -> Unit = {},
) {
    val local = state.seats[state.localSeat]
    val left = state.seats[(state.localSeat + 1) % 4]
    val opposite = state.seats[(state.localSeat + 2) % 4]
    val right = state.seats[(state.localSeat + 3) % 4]
    Surface(modifier = modifier.fillMaxSize().testTag(COMPETITION_MAHJONG_TABLE_TAG)) {
        BoxWithConstraints {
            val compact = maxWidth < 650.dp
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onBack) { Text("返回") }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("国标麻将 · ${state.roundLabel}", style = MaterialTheme.typography.titleMedium)
                        Text("牌墙剩余 ${state.wallRemaining} 张", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = onSettings) { Text("设置") }
                }
                Text(state.status, style = MaterialTheme.typography.bodyMedium)
                state.drawnTile?.let { tile ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("competition_mahjong_drawn_tile")) {
                        Text("新摸牌", style = MaterialTheme.typography.labelMedium)
                        MahjongTile(tile, true)
                    }
                }
                if (state.feedback != null) {
                    Text(state.feedback, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("competition_mahjong_feedback"))
                }
                if (state.claim != null) {
                    Text(
                        "${state.seats[state.claim.fromSeat].name} ${state.claim.description} " +
                            competitionMahjongTileName(state.claim.tile),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("competition_mahjong_claim"),
                    )
                }
                SeatPanel(local, state.currentSeat == local.seat, true, compact)
                if (state.result != null) {
                    ResultPanel(state.result)
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .testTag("competition_mahjong_action_bar"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.availableActions.forEach { action ->
                            val enabled = state.actionsEnabled
                            Button(
                                onClick = { onAction(action.id) },
                                enabled = enabled,
                                modifier = Modifier.testTag("competition_mahjong_action_${action.id}"),
                            ) { Text(action.label) }
                        }
                    }
                }
                SeatPanel(opposite, state.currentSeat == opposite.seat, false, compact)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SeatPanel(left, state.currentSeat == left.seat, false, compact,
                        modifier = Modifier.weight(1f))
                    Card(modifier = Modifier.weight(if (compact) 0.8f else 1.4f)) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("牌桌", style = MaterialTheme.typography.titleMedium)
                            Text("庄家 ${state.seats[state.dealerSeat].name}")
                            if (state.claim != null) {
                                MahjongTile(state.claim.tile, faceUp = true)
                            }
                        }
                    }
                    SeatPanel(right, state.currentSeat == right.seat, false, compact,
                        modifier = Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onUndo, enabled = state.canUndo && !state.isBusy,
                        modifier = Modifier.testTag("competition_mahjong_undo")) {
                        Text("悔棋")
                    }
                    OutlinedButton(onClick = onRestart, enabled = state.canRestart && !state.isBusy) {
                        Text("重开")
                    }
                    if (state.autoPlay.available) {
                        OutlinedButton(
                            onClick = onToggleAutoPlay,
                            enabled = state.autoPlay.controlsEnabled,
                        ) { Text(if (state.autoPlay.running) "暂停AI" else "继续AI") }
                        COMPETITION_MAHJONG_AUTO_SPEEDS.forEach { speed ->
                            OutlinedButton(
                                onClick = { onAutoPlaySpeedChanged(speed) },
                                enabled = state.autoPlay.controlsEnabled &&
                                    state.autoPlay.speed != speed,
                            ) { Text("${speed}×") }
                        }
                        Text("其他三席 AI", modifier = Modifier.align(Alignment.CenterVertically),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeatPanel(
    seat: CompetitionMahjongSeatUi,
    active: Boolean,
    local: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${seat.windLabel} · ${seat.name}${if (active) " · 行牌" else ""}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(if (seat.netScore >= 0) "+${seat.netScore}" else "${seat.netScore}")
            }
            if (!seat.canWin) Text("本盘停和", color = MaterialTheme.colorScheme.error)
            Text("手牌 ${seat.concealedCount} 张 · 花牌 ${seat.flowers.size} 张",
                style = MaterialTheme.typography.bodySmall)
            val handTag = if (local) Modifier.testTag("competition_mahjong_local_tiles") else Modifier
            Row(modifier = handTag.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (local && seat.visibleHand != null) {
                    seat.visibleHand.forEach { MahjongTile(it, true) }
                } else {
                    repeat(seat.concealedCount) { MahjongTile(null, false) }
                }
            }
            if (seat.flowers.isNotEmpty()) {
                Text("花牌", style = MaterialTheme.typography.labelSmall)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    seat.flowers.forEach { MahjongTile(it, true) }
                }
            }
            if (seat.melds.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    seat.melds.forEach { meld ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(meld.label, style = MaterialTheme.typography.labelSmall)
                            Row { meld.tiles.forEach { MahjongTile(it, !meld.isConcealed || local || meld.isRevealed) } }
                        }
                    }
                }
            }
            if (seat.discards.isNotEmpty()) {
                Text("弃牌", style = MaterialTheme.typography.labelSmall)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    seat.discards.forEach { MahjongTile(it, true) }
                }
            }
        }
    }
}

@Composable
internal fun MahjongTile(tile: Int?, faceUp: Boolean) {
    val visible = faceUp && tile != null
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier.size(width = 30.dp, height = 39.dp)
            .background(if (visible) Color(0xFFFFF9EA) else Color(0xFF265C50), shape)
            .border(1.dp, Color(0xFF795C3E), shape)
            .semantics { contentDescription = if (visible) competitionMahjongTileName(requireNotNull(tile)) else "牌背" },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (visible) competitionMahjongTileName(requireNotNull(tile)) else "纹",
            color = if (visible) Color(0xFF222222) else Color(0xFFE3C991),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ResultPanel(result: CompetitionMahjongResultUi) {
    Card(modifier = Modifier.fillMaxWidth().testTag(COMPETITION_MAHJONG_RESULT_TAG)) {
        Column(modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(result.title, style = MaterialTheme.typography.titleMedium)
            Text(result.detail)
            if (result.winningTile != null) {
                Text("赢家手牌 · 暗手部分（含所和牌）", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .testTag("competition_mahjong_winning_tiles"),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    result.winningTiles.forEach { MahjongTile(it, true) }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("competition_mahjong_winning_tile")) {
                    Text("所和牌")
                    MahjongTile(result.winningTile, true)
                }
            }
            if (result.totalFan != null) Text("总番数 ${result.totalFan}")
            result.fans.forEach { Text("${it.name} ${it.points} × ${it.count}") }
            Text(result.netScores.indices.joinToString(" · ") {
                "${result.seatLabels[it]} ${result.netScores[it]}"
            })
        }
    }
}
