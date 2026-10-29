package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.LanzhouMahjongPhase
import com.masterofchessstrategy.engine.LanzhouMahjongPublicView
import com.masterofchessstrategy.engine.LanzhouMahjongSeatView

internal const val LANZHOU_MAHJONG_TABLE_TAG = "lanzhou_mahjong_table"

/** The local seat's concealed tiles are the only tiles supplied to a face-up hand. */
@Composable
internal fun LanzhouMahjongTableScreen(
    view: LanzhouMahjongPublicView,
    viewerSeat: Int,
    actions: List<LanzhouMahjongAction>,
    feedback: String?,
    onAction: (LanzhouMahjongAction) -> Unit,
    onBack: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    needsSave: Boolean = false,
    onRetrySave: () -> Unit = {},
    needsAdvance: Boolean = false,
    onRetryAdvance: () -> Unit = {},
    assistance: LanzhouMahjongAssistanceUsage? = null,
    canUndo: Boolean = false,
    hint: LanzhouMahjongHint? = null,
    onHint: () -> Unit = {},
    onUndo: () -> Unit = {},
    blocked: Boolean = false,
) {
    require(viewerSeat in 0..3 && view.seats.size == 4)
    val local = view.seats[viewerSeat]
    val left = view.seats[(viewerSeat + 1) % 4]
    val opposite = view.seats[(viewerSeat + 2) % 4]
    val right = view.seats[(viewerSeat + 3) % 4]
    val hand = local.concealedTiles.sorted()
    val discardActions = actions.filter { it.kind == LanzhouMahjongActionKind.DISCARD ||
        it.kind == LanzhouMahjongActionKind.READY_DISCARD }
    val selectableTiles = discardActions.mapTo(mutableSetOf()) { it.tile }
    var selectedIndex by remember(view.phase, view.currentSeat, hand, actions) {
        mutableStateOf<Int?>(null)
    }
    val selectedTile = selectedIndex?.let(hand::getOrNull)
    val selectedDiscard = discardActions.firstOrNull {
        it.kind == LanzhouMahjongActionKind.DISCARD && it.tile == selectedTile
    }
    val selectedReady = discardActions.firstOrNull {
        it.kind == LanzhouMahjongActionKind.READY_DISCARD && it.tile == selectedTile
    }
    val canAct = !isBusy && !needsSave && !needsAdvance && !blocked
    Surface(modifier.fillMaxSize().testTag(LANZHOU_MAHJONG_TABLE_TAG)) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack, enabled = !isBusy && !needsSave) { Text("返回") }
                Column(modifier = Modifier.weight(1f)) {
                    Text("兰州麻将", style = MaterialTheme.typography.titleLarge)
                    Text("庄家 ${seatName(view.dealerSeat, viewerSeat)} · 牌墙 ${view.wallRemaining} 张")
                }
                OutlinedButton(onClick = { selectedIndex = null; onRestart() },
                    enabled = !isBusy && !needsSave && !blocked) { Text("重开") }
            }
            Text(phaseLabel(view, viewerSeat), style = MaterialTheme.typography.titleMedium)
            if (isBusy) {
                Text("正在处理对局…", modifier = Modifier.testTag("lanzhou_mahjong_busy"))
            }
            feedback?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("lanzhou_mahjong_feedback")) }
            if (needsSave) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("对局保存失败，请重试", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onRetrySave, enabled = !isBusy && !blocked,
                        modifier = Modifier.testTag("lanzhou_mahjong_retry_save")) {
                        Text("重试保存")
                    }
                }
            }
            if (needsAdvance) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("对手操作暂停，请重试", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onRetryAdvance, enabled = !isBusy && !needsSave && !blocked,
                        modifier = Modifier.testTag("lanzhou_mahjong_retry_advance")) {
                        Text("继续对局")
                    }
                }
            }
            SeatPanel(opposite, viewerSeat, view, local = false)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeatPanel(left, viewerSeat, view, local = false, modifier = Modifier.weight(1f))
                Card(modifier = Modifier.weight(1.2f)) {
                    Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("牌桌", style = MaterialTheme.typography.titleMedium)
                        view.pendingTile?.let { tile ->
                            Text("${seatName(view.pendingSeat ?: 0, viewerSeat)} 打出")
                            Tile(tile)
                        }
                    }
                }
                SeatPanel(right, viewerSeat, view, local = false, modifier = Modifier.weight(1f))
            }
            SeatPanel(local, viewerSeat, view, local = true,
                selectableTiles = selectableTiles, selectedIndex = selectedIndex,
                handEnabled = canAct, onSelectTile = { selectedIndex = it })
            assistance?.let { usage ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { selectedIndex = null; onUndo() },
                        enabled = canAct && canUndo &&
                            usage.remaining(LanzhouMahjongAssistanceKind.UNDO) != 0,
                        modifier = Modifier.testTag("lanzhou_mahjong_undo")) {
                        Text(assistanceLabel("悔棋", usage.remaining(LanzhouMahjongAssistanceKind.UNDO)))
                    }
                    OutlinedButton(onClick = onHint,
                        enabled = canAct && actions.isNotEmpty() &&
                            usage.remaining(LanzhouMahjongAssistanceKind.HINT) != 0,
                        modifier = Modifier.testTag("lanzhou_mahjong_hint")) {
                        Text(assistanceLabel("提示", usage.remaining(LanzhouMahjongAssistanceKind.HINT)))
                    }
                }
            }
            hint?.let { advice ->
                Card(Modifier.fillMaxWidth().testTag("lanzhou_mahjong_hint_result")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(advice.explanation)
                        Text(advice.actions.joinToString("、", transform = ::actionLabel))
                        if (advice.waits.isNotEmpty()) {
                            Text("等张：" + advice.waits.joinToString("、", transform = ::tileName))
                        }
                    }
                }
            }
            if (view.phase == LanzhouMahjongPhase.WON ||
                view.phase == LanzhouMahjongPhase.EXHAUSTIVE_DRAW) {
                val winner = view.outcome.winnerSeat
                Text(
                    when {
                        view.outcome.falseWin -> "炸胡 · 本盘结束"
                        winner != null -> "${seatName(winner, viewerSeat)} 胡牌" +
                            if (view.outcome.sevenPairs) " · 七对" else ""
                        else -> "黄庄 · 下局轮庄"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("lanzhou_mahjong_result"),
                )
            } else {
                if (discardActions.isNotEmpty()) {
                    Text(selectedTile?.let { "已选 ${tileName(it)}" } ?: "点选手牌，再打牌或报听",
                        modifier = Modifier.testTag("lanzhou_mahjong_selection"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { selectedDiscard?.let {
                            selectedIndex = null
                            onAction(it)
                        } },
                            enabled = canAct && selectedDiscard != null,
                            modifier = Modifier.testTag("lanzhou_mahjong_discard")) {
                            Text(selectedTile?.let { "打 ${tileName(it)}" } ?: "打牌")
                        }
                        if (discardActions.any { it.kind == LanzhouMahjongActionKind.READY_DISCARD }) {
                            OutlinedButton(onClick = { selectedReady?.let {
                                selectedIndex = null
                                onAction(it)
                            } },
                                enabled = canAct && selectedReady != null,
                                modifier = Modifier.testTag("lanzhou_mahjong_ready_discard")) {
                                Text("报听")
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    actions.filterNot { it in discardActions }.forEach { action ->
                        Button(onClick = { onAction(action) }, enabled = canAct,
                            modifier = Modifier.testTag("lanzhou_mahjong_action_${action.kind.code}_${action.tile}")) {
                            Text(actionLabel(action))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeatPanel(
    seat: LanzhouMahjongSeatView,
    viewerSeat: Int,
    view: LanzhouMahjongPublicView,
    local: Boolean,
    modifier: Modifier = Modifier,
    selectableTiles: Set<Int> = emptySet(),
    selectedIndex: Int? = null,
    handEnabled: Boolean = false,
    onSelectTile: (Int) -> Unit = {},
) {
    Card(modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            val turn = if (view.currentSeat == seat.seat) " · 行牌" else ""
            Text("${seatName(seat.seat, viewerSeat)}$turn · ${formatScore(view.netScores[seat.seat])}",
                style = MaterialTheme.typography.titleSmall)
            Text("手牌 ${seat.concealedCount} 张${if (seat.ready) " · 已报听" else ""}")
            if (seat.melds.isNotEmpty()) {
                Text("碰杠 " + seat.melds.joinToString(" ") { meld ->
                    val label = when (meld.kind.code) { 0 -> "碰"; 1 -> "明杠"; else -> "暗杠" }
                    if (meld.isTileHidden) label else "$label${tileName(meld.tile)}"
                })
            }
            if (seat.discards.isNotEmpty()) {
                Text("弃牌 " + seat.discards.joinToString(" ", transform = ::tileName))
            }
            if (local) {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    seat.concealedTiles.sorted().forEachIndexed { index, tile ->
                        Tile(tile, Modifier.testTag("lanzhou_mahjong_hand_$index"),
                            selected = index == selectedIndex,
                            enabled = handEnabled && tile in selectableTiles,
                            onClick = { onSelectTile(index) })
                    }
                }
            } else {
                Text("暗牌", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Tile(
    tile: Int,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val interaction = if (onClick == null) modifier else modifier.selectable(
        selected = selected, enabled = enabled, role = Role.Button, onClick = onClick,
    )
    Box(modifier = interaction.size(width = 48.dp, height = 56.dp)
        .background(if (selected) Color(0xFFDAEED5) else Color(0xFFF8F3E8), RoundedCornerShape(4.dp))
        .border(if (selected) 3.dp else 1.dp,
            if (selected) Color(0xFF245B35) else Color(0xFF576A60), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center) {
        Text(tileName(tile), color = Color(0xFF213D30).copy(
            alpha = if (onClick != null && !enabled) 0.55f else 1f))
    }
}

internal fun tileName(tile: Int): String {
    require(tile in 0..26)
    val suit = when (tile / 9) { 0 -> "万"; 1 -> "条"; else -> "筒" }
    return "${tile % 9 + 1}$suit"
}

private fun seatName(seat: Int, viewerSeat: Int): String =
    if (seat == viewerSeat) "我" else "${seat + 1}号位"

private fun formatScore(score: Long): String = if (score >= 0) "+$score" else "$score"

private fun assistanceLabel(label: String, remaining: Int?): String =
    if (remaining == null) "$label · 不限" else "$label · 余$remaining"

private fun phaseLabel(view: LanzhouMahjongPublicView, viewerSeat: Int): String = when (view.phase) {
    LanzhouMahjongPhase.DISCARD -> "${seatName(view.currentSeat, viewerSeat)} 出牌"
    LanzhouMahjongPhase.DRAW -> "${seatName(view.currentSeat, viewerSeat)} 摸牌"
    LanzhouMahjongPhase.CLAIM -> "等待碰、杠或胡牌回应"
    LanzhouMahjongPhase.ROB_KONG -> "等待抢杠回应"
    LanzhouMahjongPhase.WON -> "本盘结束"
    LanzhouMahjongPhase.EXHAUSTIVE_DRAW -> "黄庄"
}

private fun actionLabel(action: LanzhouMahjongAction): String = when (action.kind) {
    LanzhouMahjongActionKind.DRAW -> "摸牌"
    LanzhouMahjongActionKind.DISCARD -> "打 ${tileName(action.tile)}"
    LanzhouMahjongActionKind.READY_DISCARD -> "报听打 ${tileName(action.tile)}"
    LanzhouMahjongActionKind.PASS -> "过"
    LanzhouMahjongActionKind.PUNG -> "碰"
    LanzhouMahjongActionKind.EXPOSED_KONG -> "明杠"
    LanzhouMahjongActionKind.CONCEALED_KONG -> "暗杠 ${tileName(action.tile)}"
    LanzhouMahjongActionKind.ADDED_KONG -> "加杠 ${tileName(action.tile)}"
    LanzhouMahjongActionKind.WIN -> "胡"
}
