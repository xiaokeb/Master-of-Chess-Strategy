package com.masterofchessstrategy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.navigation.ChineseChessMode
import com.masterofchessstrategy.navigation.ModeDestination
import com.masterofchessstrategy.navigation.chineseChessDifficulties

internal const val MODE_LOCAL_GAME_TAG = "mode_local_game"
internal const val MODE_AI_GAME_TAG = "mode_ai_game"
internal const val MODE_TUTORIAL_TAG = "mode_tutorial"
internal const val DIFFICULTY_SCREEN_TAG = "difficulty_screen"
internal const val DIFFICULTY_EASY_TAG = "difficulty_easy"

@Composable
internal fun ChineseChessModeScreen(
    onBack: () -> Unit,
    onLocalGame: () -> Unit,
    onAiDifficulty: () -> Unit,
    onTutorial: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.choose_mode),
                subtitle = stringResource(R.string.chinese_chess_title),
                onBack = onBack,
            )
            ChineseChessMode.entries.chunked(2).forEach { rowModes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowModes.forEach { mode ->
                        ModeCard(
                            mode = mode,
                            onClick = when (mode.destination) {
                                ModeDestination.GAME -> onLocalGame
                                ModeDestination.DIFFICULTY -> onAiDifficulty
                                ModeDestination.TUTORIAL -> onTutorial
                                ModeDestination.LOCKED -> ({})
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowModes.size == 1) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChineseChessDifficultyScreen(
    onBack: () -> Unit,
    tutorialCompleted: Boolean,
    onDifficultySelected: (Difficulty) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(DIFFICULTY_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.choose_difficulty),
                subtitle = stringResource(
                    if (tutorialCompleted) {
                        R.string.difficulty_easy_unlocked_summary
                    } else {
                        R.string.difficulty_locked_summary
                    },
                ),
                onBack = onBack,
            )
            chineseChessDifficulties(tutorialCompleted).chunked(2).forEach { rowDifficulties ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowDifficulties.forEach { entry ->
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (entry.difficulty == Difficulty.EASY) {
                                        Modifier.testTag(DIFFICULTY_EASY_TAG)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable(
                                    enabled = entry.isUnlocked,
                                    onClick = {
                                        onDifficultySelected(entry.difficulty)
                                    },
                                ),
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(entry.difficulty.titleResource()),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    text = stringResource(entry.difficulty.requirementResource()),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = stringResource(
                                        if (entry.isUnlocked) {
                                            R.string.unlocked
                                        } else {
                                            R.string.locked
                                        },
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.back))
        }
        Column {
            Text(text = title, style = MaterialTheme.typography.headlineMedium)
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ModeCard(
    mode: ChineseChessMode,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val enabled = mode.destination != ModeDestination.LOCKED
    Card(
        modifier = modifier
            .then(
                when (mode) {
                    ChineseChessMode.LOCAL_TWO_PLAYER -> Modifier.testTag(MODE_LOCAL_GAME_TAG)
                    ChineseChessMode.HUMAN_VS_AI -> Modifier.testTag(MODE_AI_GAME_TAG)
                    ChineseChessMode.TUTORIAL -> Modifier.testTag(MODE_TUTORIAL_TAG)
                    else -> Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(mode.titleResource()),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = when (mode.destination) {
                    ModeDestination.GAME -> stringResource(R.string.available_now)
                    ModeDestination.DIFFICULTY -> stringResource(R.string.view_unlock_rules)
                    ModeDestination.TUTORIAL -> stringResource(R.string.available_now)
                    ModeDestination.LOCKED -> stringResource(R.string.future_slice)
                },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun ChineseChessMode.titleResource(): Int =
    when (this) {
        ChineseChessMode.LOCAL_TWO_PLAYER -> R.string.local_two_player
        ChineseChessMode.HUMAN_VS_AI -> R.string.human_vs_ai
        ChineseChessMode.AI_AUTO_PLAY -> R.string.ai_auto_play
        ChineseChessMode.ENDGAME -> R.string.endgame_mode
        ChineseChessMode.TUTORIAL -> R.string.tutorial_mode
    }

private fun Difficulty.titleResource(): Int =
    when (this) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
        Difficulty.MASTER -> R.string.difficulty_master
    }

private fun Difficulty.requirementResource(): Int =
    when (this) {
        Difficulty.EASY -> R.string.unlock_easy
        Difficulty.MEDIUM -> R.string.unlock_medium
        Difficulty.HARD -> R.string.unlock_hard
        Difficulty.MASTER -> R.string.unlock_master
    }
