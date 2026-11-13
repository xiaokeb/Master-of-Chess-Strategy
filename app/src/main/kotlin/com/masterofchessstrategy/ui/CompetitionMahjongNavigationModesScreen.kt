package com.masterofchessstrategy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R

internal const val COMPETITION_MAHJONG_MODES_TAG = "competition_mahjong_navigation_modes"
internal const val COMPETITION_MAHJONG_LEARNING_ENTRY_TAG = "competition_mahjong_navigation_learning"
internal const val COMPETITION_MAHJONG_MODES_BACK_TAG = "competition_mahjong_navigation_back"

/** Shared navigation only; the game owns lesson content and commit-before-completion behavior. */
@Composable
internal fun CompetitionMahjongNavigationModesScreen(
    onBack: () -> Unit,
    onLearning: () -> Unit,
    onFormal: (Int) -> Unit,
    onAutomatic: ((Int) -> Unit)? = null,
    onAutomaticArchive: ((Int) -> Unit)? = null,
    onChallenge: (() -> Unit)? = null,
) {
    var seat by rememberSaveable { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(24.dp).testTag(COMPETITION_MAHJONG_MODES_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.standard_mahjong_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.competition_mahjong_navigation_learning_note))
            Button(onClick = onLearning,
                modifier = Modifier.fillMaxWidth().testTag(COMPETITION_MAHJONG_LEARNING_ENTRY_TAG)) {
                Text(stringResource(R.string.competition_mahjong_navigation_learning))
            }
            if (onChallenge != null) {
                OutlinedButton(onClick = onChallenge, modifier = Modifier.fillMaxWidth().testTag("competition_challenge_entry")) {
                    Text("牌型训练")
                }
            }
            Text("初始座位")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                "东南西北".forEachIndexed { index, label ->
                    FilterChip(selected = seat == index, onClick = { seat = index }, label = { Text(label.toString()) },
                        modifier = Modifier.testTag("competition_formal_seat_$index"))
                }
            }
            Button(onClick = { onFormal(seat) },
                modifier = Modifier.fillMaxWidth().testTag("competition_formal_entry")) { Text("赛事大厅") }
            if (onAutomatic != null) {
                Button(onClick = { onAutomatic(seat) },
                    modifier = Modifier.fillMaxWidth().testTag("competition_auto_entry")) { Text("AI 自动演局") }
            }
            if (onAutomaticArchive != null) {
                OutlinedButton(onClick = { onAutomaticArchive(seat) },
                    modifier = Modifier.fillMaxWidth().testTag("competition_auto_archive_entry")) { Text("自动赛事棋谱") }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag(COMPETITION_MAHJONG_MODES_BACK_TAG)) {
                Text(stringResource(R.string.go_navigation_back))
            }
        }
    }
}
