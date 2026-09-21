package com.masterofchessstrategy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.opening.ChineseChessOpeningUiState

internal const val OPENING_TRAINING_SCREEN_TAG = "opening_training_screen"
internal const val OPENING_AUTO_PLAY_TAG = "opening_auto_play"

@Composable
internal fun ChineseChessOpeningScreen(
    state: ChineseChessOpeningUiState,
    onBack: () -> Unit,
    onLineSelected: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onDifficultySelected: (Difficulty) -> Unit,
    onStartAutoPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag(OPENING_TRAINING_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.opening_training_title),
                subtitle = stringResource(R.string.opening_training_subtitle),
                onBack = onBack,
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.lines, key = { it.id }) { line ->
                    FilterChip(
                        selected = line.id == state.selectedLine?.id,
                        onClick = { onLineSelected(line.id) },
                        label = { Text(line.title) },
                    )
                }
            }
            val frame = state.currentFrame
            if (frame == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (state.isLoading) {
                                R.string.opening_training_loading
                            } else {
                                R.string.opening_training_incompatible
                            },
                        ),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ChineseChessBoard(
                        state = ChineseChessGameUiState(
                            board = frame.board,
                            currentSide = frame.sideToMove,
                            result = GameResult.ONGOING,
                            isEngineAvailable = false,
                        ),
                        onSquareTap = {},
                        modifier = Modifier.weight(0.66f).fillMaxHeight(),
                    )
                    Column(
                        modifier = Modifier.weight(0.34f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            state.selectedLine?.title.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(state.selectedLine?.summary.orEmpty())
                        Text(
                            stringResource(
                                R.string.opening_training_step,
                                state.frameIndex,
                                state.frames.lastIndex,
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(frame.stepTitle, color = MaterialTheme.colorScheme.primary)
                        Text(frame.explanation, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = onPrevious,
                                enabled = state.frameIndex > 0,
                            ) { Text("<") }
                            OutlinedButton(
                                onClick = onNext,
                                enabled = state.frameIndex < state.frames.lastIndex,
                            ) { Text(">") }
                            Button(onClick = onTogglePlayback) {
                                Text(
                                    stringResource(
                                        if (state.isPlaying) R.string.record_pause
                                        else R.string.record_play,
                                    ),
                                )
                            }
                        }
                        Text(stringResource(R.string.record_speed, state.speed))
                        Slider(
                            value = state.speed,
                            onValueChange = onSpeedChange,
                            valueRange = 0.5f..4f,
                            steps = 6,
                        )
                        Text(
                            stringResource(R.string.opening_auto_play_difficulty),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(Difficulty.entries) { difficulty ->
                                FilterChip(
                                    selected = difficulty == state.difficulty,
                                    onClick = { onDifficultySelected(difficulty) },
                                    enabled = difficulty in state.unlockedDifficulties,
                                    label = { Text(difficulty.openingName()) },
                                )
                            }
                        }
                        Button(
                            onClick = onStartAutoPlay,
                            enabled =
                                state.endpointState != null &&
                                    state.difficulty in state.unlockedDifficulties,
                            modifier = Modifier.fillMaxWidth().testTag(OPENING_AUTO_PLAY_TAG),
                        ) {
                            Text(stringResource(R.string.opening_auto_play_start))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Difficulty.openingName(): String = stringResource(
    when (this) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
        Difficulty.MASTER -> R.string.difficulty_master
    },
)
