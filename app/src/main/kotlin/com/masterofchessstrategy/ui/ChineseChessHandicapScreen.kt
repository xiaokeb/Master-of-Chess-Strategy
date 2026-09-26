package com.masterofchessstrategy.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import com.masterofchessstrategy.custom.ChineseChessHandicapUiState
import com.masterofchessstrategy.custom.HandicapFeedback
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.game.ChineseChessGameUiState

internal const val HANDICAP_ENTRY_TAG = "handicap_entry"
internal const val HANDICAP_SETUP_TAG = "handicap_setup"
internal const val HANDICAP_START_TAG = "handicap_start"
internal const val HANDICAP_CONTINUE_TAG = "handicap_continue"

@Composable
internal fun ChineseChessHandicapScreen(
    state: ChineseChessHandicapUiState,
    playerSide: ChineseChessSide,
    onBack: () -> Unit,
    onSquareTap: (BoardPosition) -> Unit,
    onPresetSide: (ChineseChessSide) -> Unit,
    onPreset: (ChineseChessPieceType) -> Unit,
    onDifficulty: (Difficulty) -> Unit,
    onReset: () -> Unit,
    onStart: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize().testTag(HANDICAP_SETUP_TAG)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PageHeader(stringResource(R.string.handicap_title), stringResource(R.string.handicap_edit_hint), onBack)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(Modifier.weight(0.6f).fillMaxHeight()) {
                    Box(Modifier.fillMaxSize().padding(10.dp)) {
                        ChineseChessBoard(
                            state = ChineseChessGameUiState(
                                board = state.board, playerSide = playerSide,
                                hintedDestinations = state.removedPositions,
                                isEngineAvailable = !state.isLaunching,
                            ),
                            onSquareTap = onSquareTap,
                        )
                    }
                }
                Column(
                    Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(if (playerSide == ChineseChessSide.RED) R.string.player_red_side else R.string.player_black_side))
                    Text(stringResource(R.string.handicap_rules), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(
                        R.string.handicap_removed_count,
                        16 - state.board.count { it?.side == ChineseChessSide.RED },
                        16 - state.board.count { it?.side == ChineseChessSide.BLACK },
                    ), modifier = Modifier.testTag("handicap_removed_count"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChineseChessSide.entries.forEach { side ->
                            FilterChip(
                                selected = state.presetSide == side,
                                enabled = !state.isLaunching,
                                onClick = { onPresetSide(side) },
                                label = { Text(stringResource(if (side == ChineseChessSide.RED) R.string.red_side else R.string.black_side)) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onPreset(ChineseChessPieceType.HORSE) }, enabled = !state.isLaunching) {
                            Text(stringResource(R.string.handicap_horse))
                        }
                        OutlinedButton(onClick = { onPreset(ChineseChessPieceType.CHARIOT) }, enabled = !state.isLaunching) {
                            Text(stringResource(R.string.handicap_chariot))
                        }
                    }
                    OutlinedButton(onClick = onReset, enabled = !state.isLaunching) { Text(stringResource(R.string.handicap_reset)) }
                    Text(stringResource(R.string.choose_difficulty))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Difficulty.entries.forEach { difficulty ->
                            FilterChip(
                                selected = state.difficulty == difficulty,
                                enabled = difficulty in state.unlockedDifficulties && !state.isLaunching,
                                onClick = { onDifficulty(difficulty) },
                                label = { Text(stringResource(when (difficulty) {
                                    Difficulty.EASY -> R.string.difficulty_easy
                                    Difficulty.MEDIUM -> R.string.difficulty_medium
                                    Difficulty.HARD -> R.string.difficulty_hard
                                    Difficulty.MASTER -> R.string.difficulty_master
                                })) },
                            )
                        }
                    }
                    state.feedback?.let { feedback ->
                        Text(stringResource(when (feedback) {
                            HandicapFeedback.GENERAL_PROTECTED -> R.string.handicap_general_protected
                            HandicapFeedback.INVALID_POSITION -> R.string.handicap_invalid_position
                            HandicapFeedback.ENGINE_UNAVAILABLE -> R.string.handicap_engine_unavailable
                            HandicapFeedback.DIFFICULTY_LOCKED -> R.string.handicap_difficulty_locked
                        }), color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = onStart,
                        enabled = state.removed.isNotEmpty() && state.difficulty in state.unlockedDifficulties && !state.isLaunching,
                        modifier = Modifier.fillMaxWidth().testTag(HANDICAP_START_TAG),
                    ) { Text(stringResource(R.string.handicap_start)) }
                    if (state.savedGame != null) {
                        OutlinedButton(onClick = onContinue, enabled = !state.isLaunching,
                            modifier = Modifier.fillMaxWidth().testTag(HANDICAP_CONTINUE_TAG)) {
                            Text(stringResource(R.string.handicap_continue))
                        }
                    }
                }
            }
        }
    }
}
