package com.masterofchessstrategy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
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
import com.masterofchessstrategy.endgame.ChineseChessEndgameUiState
import com.masterofchessstrategy.endgame.EndgameCatalogFeedback
import com.masterofchessstrategy.endgame.EndgameCatalogMode
import com.masterofchessstrategy.endgame.EndgameLevelEntry
import com.masterofchessstrategy.engine.Difficulty

internal const val ENDGAME_SCREEN_TAG = "endgame_screen"
internal const val ENDGAME_LEVEL_PREFIX = "endgame_level_"

@Composable
internal fun ChineseChessEndgameScreen(
    state: ChineseChessEndgameUiState,
    onBack: () -> Unit,
    onModeSelected: (EndgameCatalogMode) -> Unit,
    onDifficultySelected: (Difficulty) -> Unit,
    onThemeSelected: (String) -> Unit,
    onRandomChallenge: () -> Unit,
    onLevelSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(ENDGAME_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.endgame_catalog_title),
                subtitle = stringResource(R.string.endgame_catalog_subtitle),
                onBack = onBack,
            )
            SelectionRow(
                entries = EndgameCatalogMode.entries,
                selected = state.mode,
                label = { it.title() },
                onSelected = onModeSelected,
            )
            SelectionRow(
                entries = Difficulty.entries,
                selected = state.selectedDifficulty,
                label = { it.title() },
                onSelected = onDifficultySelected,
            )
            if (state.mode == EndgameCatalogMode.THEME && state.themes.isNotEmpty()) {
                SelectionRow(
                    entries = state.themes,
                    selected = state.selectedTheme,
                    label = { it },
                    onSelected = onThemeSelected,
                )
            }
            if (state.mode == EndgameCatalogMode.RANDOM) {
                OutlinedButton(onClick = onRandomChallenge) {
                    Text(stringResource(R.string.endgame_random_again))
                }
            }
            state.feedback?.let {
                Text(
                    text = feedbackText(it),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.isLoading) {
                Text(stringResource(R.string.endgame_loading))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(180.dp),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.visibleEntries, key = { it.level.id }) { entry ->
                        EndgameLevelCard(entry, onLevelSelected)
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = stringResource(
                        R.string.endgame_progress_summary,
                        state.completedCount,
                        state.entries.size,
                        state.progress.totalStars,
                        state.progress.totalScore,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun <T> SelectionRow(
    entries: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        lazyRowItems(entries) { entry ->
            FilterChip(
                selected = entry == selected,
                onClick = { onSelected(entry) },
                label = { Text(label(entry)) },
            )
        }
    }
}

@Composable
private fun EndgameLevelCard(
    entry: EndgameLevelEntry,
    onLevelSelected: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .testTag(ENDGAME_LEVEL_PREFIX + entry.level.id)
            .clickable(entry.isUnlocked) { onLevelSelected(entry.level.id) },
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.endgame_level_number,
                    entry.level.chapterOrder,
                    entry.level.title,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(entry.level.theme, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    R.string.endgame_goal,
                    entry.level.maxPlayerMoves,
                    entry.level.starReward,
                    entry.level.scoreReward,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = when {
                    entry.isCompleted -> stringResource(
                        R.string.endgame_completed,
                        requireNotNull(entry.completion).bestPlayerMoves,
                    )
                    entry.isUnlocked -> stringResource(R.string.endgame_playable)
                    !entry.isChapterUnlocked -> stringResource(R.string.endgame_chapter_locked)
                    else -> stringResource(R.string.endgame_level_locked)
                },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EndgameCatalogMode.title(): String = stringResource(
    when (this) {
        EndgameCatalogMode.MAIN -> R.string.endgame_main
        EndgameCatalogMode.THEME -> R.string.endgame_theme
        EndgameCatalogMode.DAILY -> R.string.endgame_daily
        EndgameCatalogMode.RANDOM -> R.string.endgame_random
    },
)

@Composable
private fun Difficulty.title(): String = stringResource(
    when (this) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
        Difficulty.MASTER -> R.string.difficulty_master
    },
)

@Composable
private fun feedbackText(feedback: EndgameCatalogFeedback): String = stringResource(
    when (feedback) {
        EndgameCatalogFeedback.LOAD_INCOMPATIBLE -> R.string.endgame_load_incompatible
        EndgameCatalogFeedback.SAVE_FAILED -> R.string.endgame_save_failed
        EndgameCatalogFeedback.FIRST_COMPLETION -> R.string.endgame_first_completion
        EndgameCatalogFeedback.BEST_MOVES_IMPROVED -> R.string.endgame_best_improved
        EndgameCatalogFeedback.COMPLETED_AGAIN -> R.string.endgame_completed_again
    },
)
