package com.masterofchessstrategy.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameRecordCategory
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.records.ChineseChessReplayUiState
import com.masterofchessstrategy.records.GameRecordExportCodec
import com.masterofchessstrategy.records.GameRecordsFeedback
import com.masterofchessstrategy.records.GameRecordsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal const val GAME_RECORDS_SCREEN_TAG = "game_records_screen"
internal const val GAME_RECORD_REPLAY_SCREEN_TAG = "game_record_replay_screen"

@Composable
internal fun GameRecordsScreen(
    state: GameRecordsUiState,
    onBack: () -> Unit,
    onCategorySelected: (GameRecordCategory) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize().testTag(GAME_RECORDS_SCREEN_TAG)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.records_title),
                subtitle = recordsStatus(state),
                onBack = onBack,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameRecordCategory.entries.forEach { category ->
                    FilterChip(
                        selected = state.category == category,
                        onClick = { onCategorySelected(category) },
                        label = { Text(stringResource(category.titleResource())) },
                    )
                }
            }
            if (!state.isLoading && state.records.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.records_empty))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.records, key = GameRecord::recordId) { record ->
                        GameRecordCard(record, onToggleFavorite, onOpenRecord)
                    }
                }
            }
        }
    }
}

@Composable
private fun GameRecordCard(
    record: GameRecord,
    onToggleFavorite: (String) -> Unit,
    onOpenRecord: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(recordTitle(record), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.record_summary,
                        record.mode.displayName(),
                        record.difficulty?.displayName() ?: stringResource(R.string.record_no_difficulty),
                        record.moveCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(record.completedAtEpochMillis)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedButton(onClick = { onToggleFavorite(record.recordId) }) {
                Text(
                    stringResource(
                        if (record.isFavorite) R.string.record_unfavorite else R.string.record_favorite,
                    ),
                )
            }
            Button(onClick = { onOpenRecord(record.recordId) }) {
                Text(stringResource(R.string.record_replay))
            }
        }
    }
}

@Composable
internal fun ChineseChessReplayScreen(
    state: ChineseChessReplayUiState,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJumpToStart: () -> Unit,
    onJumpToEnd: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportedText = stringResource(R.string.record_exported)
    val exportFailedText = stringResource(R.string.record_export_failed)
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(GameRecordExportCodec.MIME_TYPE),
    ) { uri ->
        val record = state.record
        if (uri != null && record != null) {
            scope.launch {
                exportStatus = try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "w")?.use {
                            it.write(GameRecordExportCodec.encode(record))
                        } ?: error("Unable to open export destination")
                    }
                    exportedText
                } catch (_: RuntimeException) {
                    exportFailedText
                }
            }
        }
    }
    Surface(
        modifier = modifier.fillMaxSize().testTag(GAME_RECORD_REPLAY_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.record_replay_title),
                subtitle = replayStatus(state),
                onBack = onBack,
            )
            val frame = state.currentFrame
            if (frame == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (state.isLoading) R.string.records_loading
                            else R.string.record_incompatible,
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
                            result = if (state.frameIndex == state.frames.lastIndex) {
                                requireNotNull(state.record).result
                            } else {
                                GameResult.ONGOING
                            },
                            isEngineAvailable = false,
                        ),
                        onSquareTap = {},
                        modifier = Modifier.weight(0.68f).fillMaxHeight(),
                    )
                    Column(
                        modifier = Modifier.weight(0.32f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(
                                R.string.record_step,
                                state.frameIndex,
                                state.frames.lastIndex,
                            ),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = onJumpToStart) { Text("|<") }
                            OutlinedButton(onClick = onPrevious) { Text("<") }
                            OutlinedButton(onClick = onNext) { Text(">") }
                            OutlinedButton(onClick = onJumpToEnd) { Text(">|") }
                        }
                        Button(onClick = onTogglePlayback) {
                            Text(
                                stringResource(
                                    if (state.isPlaying) R.string.record_pause
                                    else R.string.record_play,
                                ),
                            )
                        }
                        Text(stringResource(R.string.record_speed, state.speed))
                        Slider(
                            value = state.speed,
                            onValueChange = onSpeedChange,
                            valueRange = 0.5f..4f,
                            steps = 6,
                        )
                        OutlinedButton(
                            onClick = {
                                val record = state.record ?: return@OutlinedButton
                                launcher.launch(record.recordId + GameRecordExportCodec.FILE_EXTENSION)
                            },
                        ) {
                            Text(stringResource(R.string.record_export))
                        }
                        exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun recordsStatus(state: GameRecordsUiState): String =
    when {
        state.isLoading -> stringResource(R.string.records_loading)
        state.feedback == GameRecordsFeedback.LOAD_RECOVERED -> stringResource(R.string.records_incompatible)
        state.feedback == GameRecordsFeedback.SAVE_FAILED -> stringResource(R.string.record_save_failed)
        state.feedback == GameRecordsFeedback.FAVORITE_FAILED -> stringResource(R.string.record_favorite_failed)
        else -> stringResource(R.string.records_count, state.records.size)
    }

@Composable
private fun replayStatus(state: ChineseChessReplayUiState): String =
    when {
        state.isLoading -> stringResource(R.string.records_loading)
        state.isIncompatible -> stringResource(R.string.record_incompatible)
        else -> recordTitle(requireNotNull(state.record))
    }

@Composable
private fun recordTitle(record: GameRecord): String =
    stringResource(R.string.record_title, record.result.displayName())

private fun GameRecordCategory.titleResource(): Int = when (this) {
    GameRecordCategory.ALL -> R.string.records_all
    GameRecordCategory.FAVORITES -> R.string.records_favorites
    GameRecordCategory.ENDGAME -> R.string.records_endgame
}

@Composable
private fun StoredGameMode.displayName(): String = stringResource(
    when (this) {
        StoredGameMode.LOCAL_TWO_PLAYER -> R.string.local_two_player
        StoredGameMode.HUMAN_VS_AI -> R.string.human_vs_ai
        StoredGameMode.AI_AUTO_PLAY -> R.string.ai_auto_play
        StoredGameMode.ENDGAME -> R.string.endgame_mode
        StoredGameMode.TUTORIAL -> R.string.tutorial_mode
        StoredGameMode.CUSTOM_POSITION -> R.string.custom_position_mode
        StoredGameMode.TIMED_CHALLENGE -> R.string.timed_challenge_mode
        StoredGameMode.STREAK_CHALLENGE -> R.string.streak_challenge_mode
        StoredGameMode.BLIND_CHALLENGE -> R.string.blind_challenge_mode
    },
)

@Composable
private fun Difficulty.displayName(): String = stringResource(
    when (this) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
        Difficulty.MASTER -> R.string.difficulty_master
    },
)

@Composable
private fun GameResult.displayName(): String = stringResource(
    when (this) {
        GameResult.FIRST_PLAYER_WIN -> R.string.red_wins
        GameResult.SECOND_PLAYER_WIN -> R.string.black_wins
        GameResult.DRAW -> R.string.draw_game
        GameResult.ONGOING -> R.string.game_ongoing
    },
)
