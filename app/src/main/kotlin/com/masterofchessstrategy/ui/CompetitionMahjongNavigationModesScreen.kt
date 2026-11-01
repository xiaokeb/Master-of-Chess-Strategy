package com.masterofchessstrategy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
internal fun CompetitionMahjongNavigationModesScreen(onBack: () -> Unit, onLearning: () -> Unit) {
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
            Text(stringResource(R.string.competition_mahjong_navigation_pending))
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag(COMPETITION_MAHJONG_MODES_BACK_TAG)) {
                Text(stringResource(R.string.go_navigation_back))
            }
        }
    }
}
