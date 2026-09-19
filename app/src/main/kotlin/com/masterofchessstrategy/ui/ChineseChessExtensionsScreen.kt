package com.masterofchessstrategy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R
import com.masterofchessstrategy.challenge.ChineseChessTimedChallengeUiState
import com.masterofchessstrategy.challenge.ChineseChessStreakUiState
import com.masterofchessstrategy.challenge.TimedChallengeConfig
import com.masterofchessstrategy.custom.ChineseChessSetupFeedback
import com.masterofchessstrategy.custom.ChineseChessSetupUiState
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.game.ChineseChessGameUiState

internal const val EXTENSIONS_SCREEN_TAG = "extensions_screen"
internal const val TIMED_CHALLENGE_ENTRY_TAG = "timed_challenge_entry"
internal const val TIMED_CHALLENGE_SETUP_TAG = "timed_challenge_setup"
internal const val TIMED_CHALLENGE_START_TAG = "timed_challenge_start"
internal const val STREAK_CHALLENGE_ENTRY_TAG = "streak_challenge_entry"
internal const val STREAK_CHALLENGE_SETUP_TAG = "streak_challenge_setup"
internal const val STREAK_CHALLENGE_START_TAG = "streak_challenge_start"
internal const val CUSTOM_SETUP_ENTRY_TAG = "custom_setup_entry"
internal const val CUSTOM_SETUP_SCREEN_TAG = "custom_setup_screen"
internal const val CUSTOM_SETUP_START_TAG = "custom_setup_start"

private enum class ExtensionEntry(val available: Boolean) {
    TIMED(true),
    STREAK(true),
    BLIND(false),
    CUSTOM_POSITION(true),
    ASSESSMENT(false),
    OPENING(false),
}

@Composable
internal fun ChineseChessExtensionsScreen(
    onBack: () -> Unit,
    onTimedChallenge: () -> Unit,
    onStreakChallenge: () -> Unit,
    onCustomPosition: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(EXTENSIONS_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.extension_mode),
                subtitle = stringResource(R.string.extension_subtitle),
                onBack = onBack,
            )
            ExtensionEntry.entries.chunked(3).forEach { entries ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    entries.forEach { entry ->
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    when (entry) {
                                        ExtensionEntry.TIMED ->
                                            Modifier.testTag(TIMED_CHALLENGE_ENTRY_TAG)
                                        ExtensionEntry.STREAK ->
                                            Modifier.testTag(STREAK_CHALLENGE_ENTRY_TAG)
                                        ExtensionEntry.CUSTOM_POSITION ->
                                            Modifier.testTag(CUSTOM_SETUP_ENTRY_TAG)
                                        else -> Modifier
                                    },
                                )
                                .clickable(
                                    enabled = entry.available,
                                    onClick = {
                                        when (entry) {
                                            ExtensionEntry.TIMED -> onTimedChallenge()
                                            ExtensionEntry.STREAK -> onStreakChallenge()
                                            ExtensionEntry.CUSTOM_POSITION -> onCustomPosition()
                                            else -> Unit
                                        }
                                    },
                                ),
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(entry.titleResource()),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    text = stringResource(entry.summaryResource()),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    text = stringResource(
                                        if (entry.available) {
                                            R.string.available_now
                                        } else {
                                            R.string.future_slice
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
internal fun ChineseChessStreakChallengeScreen(
    state: ChineseChessStreakUiState,
    onBack: () -> Unit,
    onDifficultySelected: (Difficulty) -> Unit,
    onStart: () -> Unit,
    onContinueSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(STREAK_CHALLENGE_SETUP_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.streak_challenge_title),
                subtitle = stringResource(R.string.streak_challenge_subtitle),
                onBack = onBack,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.streak_choose_starting_difficulty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ChoiceRow(
                        entries = Difficulty.entries,
                        selected = state.startingDifficulty,
                        enabled = { it in state.unlockedDifficulties },
                        label = { it.difficultyName() },
                        onSelected = onDifficultySelected,
                    )
                    Text(
                        stringResource(R.string.streak_challenge_rules),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onStart,
                        enabled = state.unlockedDifficulties.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(STREAK_CHALLENGE_START_TAG),
                    ) {
                        Text(stringResource(R.string.streak_challenge_start))
                    }
                    state.savedChallenge?.let { saved ->
                        OutlinedButton(
                            onClick = onContinueSaved,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    R.string.streak_challenge_continue,
                                    saved.state.currentStreak,
                                    saved.state.bestStreak,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChineseChessTimedChallengeScreen(
    state: ChineseChessTimedChallengeUiState,
    onBack: () -> Unit,
    onDifficultySelected: (Difficulty) -> Unit,
    onSecondsSelected: (Int) -> Unit,
    onStart: () -> Unit,
    onContinueSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(TIMED_CHALLENGE_SETUP_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.timed_challenge_title),
                subtitle = stringResource(R.string.timed_challenge_subtitle),
                onBack = onBack,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.choose_difficulty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ChoiceRow(
                        entries = Difficulty.entries,
                        selected = state.difficulty,
                        enabled = { it in state.unlockedDifficulties },
                        label = { it.difficultyName() },
                        onSelected = onDifficultySelected,
                    )
                    Text(
                        stringResource(R.string.timed_challenge_choose_clock),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ChoiceRow(
                        entries = TimedChallengeConfig.ALLOWED_SECONDS,
                        selected = state.secondsPerMove,
                        label = {
                            stringResource(R.string.timed_challenge_seconds, it)
                        },
                        onSelected = onSecondsSelected,
                    )
                    Text(
                        stringResource(R.string.timed_challenge_rules),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onStart,
                        enabled = state.unlockedDifficulties.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(TIMED_CHALLENGE_START_TAG),
                    ) {
                        Text(stringResource(R.string.timed_challenge_start))
                    }
                    state.savedChallenge?.let { saved ->
                        OutlinedButton(
                            onClick = onContinueSaved,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    R.string.timed_challenge_continue,
                                    saved.difficulty.difficultyName(),
                                    saved.secondsPerMove,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChineseChessSetupScreen(
    state: ChineseChessSetupUiState,
    onBack: () -> Unit,
    onSquareTap: (BoardPosition) -> Unit,
    onSideSelected: (ChineseChessSide) -> Unit,
    onPieceSelected: (ChineseChessPieceType?) -> Unit,
    onSideToMoveSelected: (ChineseChessSide) -> Unit,
    onDifficultySelected: (Difficulty) -> Unit,
    onClear: () -> Unit,
    onResetStandard: () -> Unit,
    onStart: () -> Unit,
    onContinueSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(CUSTOM_SETUP_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.custom_position_title),
                subtitle = stringResource(R.string.custom_position_subtitle),
                onBack = onBack,
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChineseChessBoard(
                            state = ChineseChessGameUiState(
                                board = state.board,
                                currentSide = state.sideToMove,
                            ),
                            onSquareTap = onSquareTap,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 360.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.custom_position_piece_side),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ChoiceRow(
                        entries = ChineseChessSide.entries,
                        selected = state.selectedSide,
                        label = { it.sideName() },
                        onSelected = onSideSelected,
                    )
                    Text(
                        stringResource(R.string.custom_position_piece_type),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ChoiceRow(
                        entries = ChineseChessPieceType.entries,
                        selected = state.selectedPieceType,
                        label = { it.pieceName() },
                        onSelected = onPieceSelected,
                    )
                    FilterChip(
                        selected = state.selectedPieceType == null,
                        onClick = { onPieceSelected(null) },
                        label = { Text(stringResource(R.string.custom_position_eraser)) },
                    )
                    Text(
                        stringResource(R.string.custom_position_side_to_move),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ChoiceRow(
                        entries = ChineseChessSide.entries,
                        selected = state.sideToMove,
                        label = { it.sideName() },
                        onSelected = onSideToMoveSelected,
                    )
                    Text(
                        stringResource(R.string.choose_difficulty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    ChoiceRow(
                        entries = Difficulty.entries,
                        selected = state.difficulty,
                        enabled = { it in state.unlockedDifficulties },
                        label = { it.difficultyName() },
                        onSelected = onDifficultySelected,
                    )
                    state.feedback?.let { feedback ->
                        Text(
                            text = stringResource(feedback.messageResource()),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.custom_position_clear))
                        }
                        OutlinedButton(
                            onClick = onResetStandard,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.custom_position_standard))
                        }
                    }
                    Button(
                        onClick = onStart,
                        enabled = state.unlockedDifficulties.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(CUSTOM_SETUP_START_TAG),
                    ) {
                        Text(stringResource(R.string.custom_position_start))
                    }
                    if (state.savedGameAvailable) {
                        OutlinedButton(
                            onClick = onContinueSaved,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.custom_position_continue))
                        }
                    }
                    Text(
                        text = stringResource(R.string.custom_position_boundary),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    entries: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(entries) { entry ->
            FilterChip(
                selected = selected == entry,
                onClick = { onSelected(entry) },
                enabled = enabled(entry),
                label = { Text(label(entry)) },
            )
        }
    }
}

private fun ExtensionEntry.titleResource(): Int = when (this) {
    ExtensionEntry.TIMED -> R.string.extension_timed
    ExtensionEntry.STREAK -> R.string.extension_streak
    ExtensionEntry.BLIND -> R.string.extension_blind
    ExtensionEntry.CUSTOM_POSITION -> R.string.extension_custom_position
    ExtensionEntry.ASSESSMENT -> R.string.extension_assessment
    ExtensionEntry.OPENING -> R.string.extension_opening
}

private fun ExtensionEntry.summaryResource(): Int = when (this) {
    ExtensionEntry.TIMED -> R.string.extension_timed_summary
    ExtensionEntry.STREAK -> R.string.extension_streak_summary
    ExtensionEntry.BLIND -> R.string.extension_blind_summary
    ExtensionEntry.CUSTOM_POSITION -> R.string.extension_custom_position_summary
    ExtensionEntry.ASSESSMENT -> R.string.extension_assessment_summary
    ExtensionEntry.OPENING -> R.string.extension_opening_summary
}

@Composable
private fun ChineseChessSide.sideName(): String = stringResource(
    if (this == ChineseChessSide.RED) R.string.red_side else R.string.black_side,
)

@Composable
private fun ChineseChessPieceType.pieceName(): String = stringResource(
    when (this) {
        ChineseChessPieceType.GENERAL -> R.string.piece_general
        ChineseChessPieceType.ADVISOR -> R.string.piece_advisor
        ChineseChessPieceType.ELEPHANT -> R.string.piece_elephant
        ChineseChessPieceType.HORSE -> R.string.piece_horse
        ChineseChessPieceType.CHARIOT -> R.string.piece_chariot
        ChineseChessPieceType.CANNON -> R.string.piece_cannon
        ChineseChessPieceType.SOLDIER -> R.string.piece_soldier
    },
)

@Composable
private fun Difficulty.difficultyName(): String = stringResource(
    when (this) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
        Difficulty.MASTER -> R.string.difficulty_master
    },
)

private fun ChineseChessSetupFeedback.messageResource(): Int = when (this) {
    ChineseChessSetupFeedback.INVALID_PIECE_COUNT -> R.string.custom_position_invalid_count
    ChineseChessSetupFeedback.INVALID_PLACEMENT -> R.string.custom_position_invalid_placement
    ChineseChessSetupFeedback.MISSING_GENERALS -> R.string.custom_position_missing_generals
    ChineseChessSetupFeedback.POSITION_NOT_PLAYABLE -> R.string.custom_position_not_playable
    ChineseChessSetupFeedback.ENGINE_UNAVAILABLE -> R.string.custom_position_engine_unavailable
}
