package com.masterofchessstrategy.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R
import com.masterofchessstrategy.navigation.HomeGameEntry

internal const val HOME_CHINESE_CHESS_TAG = "home_chinese_chess"

internal data class LocalPlayerSummary(
    val rank: String,
    val wins: Int,
    val stars: Int,
    val score: Int,
)

private val InitialPlayerSummary = LocalPlayerSummary(
    rank = "未定级",
    wins = 0,
    stars = 0,
    score = 0,
)

@Composable
internal fun HomeScreen(
    onGameSelected: (HomeGameEntry) -> Unit,
    quickStartEntries: Set<HomeGameEntry>,
    onQuickStart: (HomeGameEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            if (maxWidth >= 720.dp) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerSummaryCard(
                        summary = InitialPlayerSummary,
                        modifier = Modifier.weight(0.4f),
                    )
                    GameCatalog(
                        onGameSelected = onGameSelected,
                        quickStartEntries = quickStartEntries,
                        onQuickStart = onQuickStart,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PlayerSummaryCard(
                        summary = InitialPlayerSummary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GameCatalog(
                        onGameSelected = onGameSelected,
                        quickStartEntries = quickStartEntries,
                        onQuickStart = onQuickStart,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSummaryCard(
    summary: LocalPlayerSummary,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = stringResource(R.string.offline_profile),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.profile_rank, summary.rank))
            Text(stringResource(R.string.profile_wins, summary.wins))
            Text(stringResource(R.string.profile_stars, summary.stars))
            Text(stringResource(R.string.profile_score, summary.score))
        }
    }
}

@Composable
private fun GameCatalog(
    onGameSelected: (HomeGameEntry) -> Unit,
    quickStartEntries: Set<HomeGameEntry>,
    onQuickStart: (HomeGameEntry) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.choose_game),
            style = MaterialTheme.typography.headlineMedium,
        )
        HomeGameEntry.entries.chunked(2).forEach { rowEntries ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowEntries.forEach { entry ->
                    GameEntryCard(
                        entry = entry,
                        onClick = { onGameSelected(entry) },
                        onLongClick = if (entry in quickStartEntries) {
                            { onQuickStart(entry) }
                        } else {
                            null
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowEntries.size == 1) {
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GameEntryCard(
    entry: HomeGameEntry,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
) {
    Card(
        modifier = modifier
            .then(
                if (entry == HomeGameEntry.CHINESE_CHESS) {
                    Modifier.testTag(HOME_CHINESE_CHESS_TAG)
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                enabled = entry.isAvailable,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(entry.titleResource()),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = if (entry.isAvailable) {
                    if (onLongClick == null) {
                        stringResource(R.string.enter_modes)
                    } else {
                        stringResource(R.string.enter_modes_or_quick_start)
                    }
                } else {
                    stringResource(R.string.future_slice)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun HomeGameEntry.titleResource(): Int =
    when (this) {
        HomeGameEntry.CHINESE_CHESS -> R.string.chinese_chess_title
        HomeGameEntry.GO -> R.string.go_title
        HomeGameEntry.STANDARD_MAHJONG -> R.string.standard_mahjong_title
        HomeGameEntry.LANZHOU_MAHJONG -> R.string.lanzhou_mahjong_title
        HomeGameEntry.LANZHOU_TRADITIONAL -> R.string.lanzhou_traditional_title
    }
