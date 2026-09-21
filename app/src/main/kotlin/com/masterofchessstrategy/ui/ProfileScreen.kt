package com.masterofchessstrategy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.progress.PlayerAppearance
import com.masterofchessstrategy.progress.PlayerGrowthSummary

internal const val PROFILE_SCREEN_TAG = "profile_screen"
internal const val PROFILE_APPEARANCE_PREFIX = "profile_appearance_"

@Composable
internal fun ProfileScreen(
    summary: PlayerGrowthSummary,
    selectedAppearanceCode: Int,
    onSelectAppearance: (PlayerAppearance) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requested = PlayerAppearance.fromCode(selectedAppearanceCode)
    val selected = requested.takeIf { it in summary.unlockedAppearances }
        ?: PlayerAppearance.BAMBOO_STUDENT
    Surface(modifier = modifier.fillMaxSize().testTag(PROFILE_SCREEN_TAG)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineMedium)
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
            }
            BoxWithConstraints {
                if (maxWidth >= 720.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OverviewCard(summary, selected, Modifier.weight(0.42f))
                        StatisticsCard(summary, Modifier.weight(0.58f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OverviewCard(summary, selected, Modifier.fillMaxWidth())
                        StatisticsCard(summary, Modifier.fillMaxWidth())
                    }
                }
            }
            Text(stringResource(R.string.profile_achievements), style = MaterialTheme.typography.titleLarge)
            summary.achievements.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { achievement ->
                        Card(modifier = Modifier.weight(1f)) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(achievement.title, style = MaterialTheme.typography.titleMedium)
                                Text(achievement.description, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (achievement.isUnlocked) {
                                        stringResource(R.string.profile_achievement_unlocked)
                                    } else {
                                        stringResource(R.string.profile_achievement_locked)
                                    },
                                    color = if (achievement.isUnlocked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                )
                            }
                        }
                    }
                    if (row.size == 1) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
            Text(stringResource(R.string.profile_appearances), style = MaterialTheme.typography.titleLarge)
            PlayerAppearance.entries.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    row.forEach { appearance ->
                        AppearanceCard(
                            appearance = appearance,
                            isUnlocked = appearance in summary.unlockedAppearances,
                            isSelected = appearance == selected,
                            onSelect = { onSelectAppearance(appearance) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.profile_offline_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverviewCard(
    summary: PlayerGrowthSummary,
    selected: PlayerAppearance,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerPortrait(selected, Modifier.size(150.dp))
            Text(selected.title, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.profile_rank, summary.rank.title))
            Text(stringResource(R.string.profile_wins, summary.statistics.totalWins))
            Text(
                stringResource(
                    R.string.profile_total_rewards,
                    summary.totalStars,
                    summary.totalScore,
                ),
            )
        }
    }
}

@Composable
private fun StatisticsCard(summary: PlayerGrowthSummary, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(stringResource(R.string.profile_game_statistics), style = MaterialTheme.typography.titleLarge)
            GameType.entries.forEach { type ->
                val statistics = summary.gameStatistics(type)
                Text(type.displayName(), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.profile_record,
                        statistics.completedMatches,
                        statistics.wins,
                        statistics.draws,
                        statistics.losses,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AppearanceCard(
    appearance: PlayerAppearance,
    isUnlocked: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier,
) {
    Card(modifier = modifier.testTag(PROFILE_APPEARANCE_PREFIX + appearance.code)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlayerPortrait(appearance, Modifier.size(88.dp))
            Text(appearance.title, style = MaterialTheme.typography.titleSmall)
            when {
                isSelected -> Text(
                    stringResource(R.string.profile_appearance_selected),
                    color = MaterialTheme.colorScheme.primary,
                )
                isUnlocked -> Button(onClick = onSelect) {
                    Text(stringResource(R.string.profile_appearance_use))
                }
                else -> Text(
                    stringResource(
                        R.string.profile_appearance_locked,
                        appearance.requiredRank.title,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PlayerPortrait(appearance: PlayerAppearance, modifier: Modifier) {
    val palettes = listOf(
        Triple(Color(0xFF2E6B4F), Color(0xFFE6C89C), Color(0xFF183A2B)),
        Triple(Color(0xFF3E6D8E), Color(0xFFE1B98A), Color(0xFF213747)),
        Triple(Color(0xFF28766B), Color(0xFFF0C79C), Color(0xFF15453F)),
        Triple(Color(0xFF374563), Color(0xFFE4BB91), Color(0xFF1E2637)),
        Triple(Color(0xFF9A6B22), Color(0xFFF0C38F), Color(0xFF533912)),
        Triple(Color(0xFF64508C), Color(0xFFF2CBA0), Color(0xFF352A4A)),
    )
    val (robe, skin, ink) = palettes[appearance.code]
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        drawCircle(robe.copy(alpha = 0.16f), radius)
        drawCircle(ink, radius * 0.24f, Offset(size.width / 2f, size.height * 0.26f))
        drawCircle(skin, radius * 0.22f, Offset(size.width / 2f, size.height * 0.34f))
        drawRoundRect(
            color = robe,
            topLeft = Offset(size.width * 0.23f, size.height * 0.52f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.54f, size.height * 0.38f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * 0.18f),
        )
        drawLine(
            color = ink.copy(alpha = 0.75f),
            start = Offset(size.width * 0.5f, size.height * 0.54f),
            end = Offset(size.width * 0.5f, size.height * 0.86f),
            strokeWidth = size.width * 0.035f,
        )
        drawCircle(
            color = ink,
            radius = radius * 0.055f,
            center = Offset(size.width * 0.42f, size.height * 0.34f),
        )
        drawCircle(
            color = ink,
            radius = radius * 0.055f,
            center = Offset(size.width * 0.58f, size.height * 0.34f),
        )
    }
}

private fun GameType.displayName(): String = when (this) {
    GameType.CHINESE_CHESS -> "中国象棋"
    GameType.GO -> "围棋"
    GameType.STANDARD_MAHJONG -> "国标麻将"
    GameType.LANZHOU_MAHJONG -> "兰州麻将"
    GameType.LANZHOU_SQUARE_CHESS -> "兰州方棋"
    GameType.TIGER_AND_GOAT -> "老虎吃羊棋"
}
