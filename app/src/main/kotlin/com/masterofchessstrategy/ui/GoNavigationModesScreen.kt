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

internal const val GO_MODES_TAG = "go_navigation_modes"
internal const val GO_LOCAL_ENTRY_TAG = "go_navigation_local"
internal const val GO_LEARNING_ENTRY_TAG = "go_navigation_learning"
internal const val GO_MODES_BACK_TAG = "go_navigation_back"

/** Shared entry orchestration; Go owns its game, rules and session implementation. */
@Composable
internal fun GoNavigationModesScreen(
    onBack: () -> Unit,
    onLocalGame: () -> Unit,
    onLearning: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(24.dp).testTag(GO_MODES_TAG),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.go_navigation_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.go_navigation_local_description))
            Button(onClick = onLocalGame, modifier = Modifier.fillMaxWidth().testTag(GO_LOCAL_ENTRY_TAG)) {
                Text(stringResource(R.string.go_navigation_local))
            }
            Text(stringResource(R.string.go_navigation_scoring_note))
            Text(stringResource(R.string.go_navigation_sgf_note))
            Button(onClick = onLearning, modifier = Modifier.fillMaxWidth().testTag(GO_LEARNING_ENTRY_TAG)) {
                Text(stringResource(R.string.go_navigation_learning))
            }
            Text(stringResource(R.string.go_navigation_learning_note))
            Text(stringResource(R.string.go_navigation_future), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag(GO_MODES_BACK_TAG)) {
                Text(stringResource(R.string.go_navigation_back))
            }
        }
    }
}
