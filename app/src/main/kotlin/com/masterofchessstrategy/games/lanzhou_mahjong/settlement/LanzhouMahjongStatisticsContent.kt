package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

@Composable
internal fun LanzhouMahjongStatisticsContent(statistics: LanzhouMahjongStatistics) {
    Text("正式对局统计", style = MaterialTheme.typography.titleMedium)
    if (statistics.completedRounds == 0) {
        Text("完成正式对局后，四席结算会显示在这里。")
        return
    }
    Text("已结算 ${statistics.completedRounds} 局 · 黄庄 ${statistics.exhaustiveDraws} 局")
    statistics.seats.forEachIndexed { index, seat ->
        val score = if (seat.netScore.signum() > 0) "+${seat.netScore}" else seat.netScore.toString()
        Text(
            "${index + 1} 号席 · 胡牌 ${seat.wins} 次 · 炸胡 ${seat.falseDeclarations} 次 · 净分 $score",
            modifier = Modifier.testTag("lanzhou_mahjong_statistics_seat_$index"),
        )
    }
}
