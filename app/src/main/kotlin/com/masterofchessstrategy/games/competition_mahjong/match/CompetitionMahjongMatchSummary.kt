package com.masterofchessstrategy.games.competition_mahjong.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.math.BigDecimal

/** Public series summary; current private engine bytes never enter this composable. */
@Composable
fun CompetitionMahjongMatchSummary(
    state: CompetitionMahjongCompetitionState,
    onNextHand: (Int) -> Unit,
    onRetrySave: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
) {
    val winds = listOf("东", "南", "西", "北")
    fun player(id: Int) = if (id == state.viewerPlayerId) "你" else "玩家${id + 1}"
    Card(modifier = modifier.fillMaxWidth().testTag("competition_mahjong_match_summary")) {
        Column(
            modifier = Modifier.padding(12.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("国标麻将 · 十六盘", style = MaterialTheme.typography.titleMedium)
            Text("已完成 ${state.completedHandCount} / 16 盘", modifier = Modifier.testTag("competition_mahjong_match_progress"))
            if (state.completedHandCount < 16) {
                Text("${winds[state.activeHandIndex / 4]}风圈 · 第${state.activeHandIndex % 4 + 1}盘")
                val seating = CompetitionMahjongMatchSeating.forHand(state.activeHandIndex)
                Text("本圈座次（东南西北）：${(0..3).joinToString("、") { player(seating.playerAtSeat(it)) }}")
            }
            Text("累计比赛分", style = MaterialTheme.typography.titleSmall)
            if (state.standings.isEmpty()) {
                repeat(4) { id ->
                    Text("${player(id)}：${state.cumulativeScores[id]}", modifier = Modifier.testTag("competition_mahjong_match_score_$id"))
                }
            } else {
                state.standings.forEach { standing ->
                    val points = standardPointsLabel(standing.standardPointsNumerator, standing.standardPointsDenominator)
                    Text(
                        "第${standing.rank}名 ${player(standing.playerId)}：${standing.score} · 标准分 $points",
                        modifier = Modifier.testTag("competition_mahjong_match_score_${standing.playerId}"),
                    )
                }
                Text("同分并列，共享所占名次的标准分。")
            }
            if (state.error != null) Text(state.error, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.needsSave) {
                    Button(onClick = onRetrySave, enabled = !busy, modifier = Modifier.testTag("competition_mahjong_match_retry")) {
                        Text("重试保存")
                    }
                } else if (state.completedHandCount in 1..15 && state.completedHandCount == state.activeHandIndex + 1) {
                    Button(
                        onClick = { onNextHand(state.activeHandIndex) },
                        enabled = !busy && state.canStartNextHand,
                        modifier = Modifier.testTag("competition_mahjong_match_next"),
                    ) { Text("下一盘") }
                }
            }
            if (state.needsSave) Text("本盘尚未保存，保存成功后可继续。")
            if (state.completedHandCount == 16 && !state.needsSave) Text("十六盘结束。")
        }
    }
}

private fun standardPointsLabel(numerator: Int, denominator: Int): String =
    if (denominator == 3 && numerator % denominator != 0) "$numerator/$denominator" else
        BigDecimal(numerator).divide(BigDecimal(denominator)).stripTrailingZeros().toPlainString()
