package com.masterofchessstrategy.games.competition_mahjong.challenge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterofchessstrategy.games.competition_mahjong.MahjongTile
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongTileName

@Composable
fun CompetitionMahjongChallengeRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: CompetitionMahjongChallengeProgressRepository,
    ownerKey: String,
    onWriterReady: (CompetitionMahjongChallengeViewModel) -> Unit,
) {
    val factory = remember(repository, ownerKey) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CompetitionMahjongChallengeViewModel(repository).also(onWriterReady) as T
        }
    }
    val game: CompetitionMahjongChallengeViewModel = viewModel(
        key = ownerKey, factory = factory)
    SideEffect { onWriterReady(game) }
    val state by game.state.collectAsState()
    BackHandler(onBack = onBack)
    CompetitionMahjongChallengeScreen(
        state = state,
        onAnswer = { game.answer(it) },
        onSelect = { game.select(it) },
        onThemedSelect = { game.select(it, CompetitionMahjongChallengeMode.THEME) },
        onDaily = { game.openDaily() },
        onRandom = { theme, level -> game.openRandom(theme, level) },
        onReset = { game.reset() },
        onRetry = { game.retry() },
        onBack = onBack,
        modifier = modifier,
        onDiscard = { game.discard(it) },
        onConfirmWin = { game.confirmWin() },
    )
}

@Composable
internal fun CompetitionMahjongChallengeScreen(
    state: CompetitionMahjongChallengeUiState,
    onAnswer: (String) -> Unit,
    onSelect: (String) -> Unit,
    onThemedSelect: (String) -> Unit,
    onDaily: () -> Unit,
    onRandom: (CompetitionMahjongChallengeTheme?, CompetitionMahjongChallengeLevel?) -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDiscard: (Int) -> Unit = {},
    onConfirmWin: () -> Unit = {},
) {
    var catalogOpen by remember { mutableStateOf(false) }
    var theme by remember { mutableStateOf<CompetitionMahjongChallengeTheme?>(null) }
    var level by remember { mutableStateOf<CompetitionMahjongChallengeLevel?>(null) }
    val exercise = state.exercise
    Surface(modifier.fillMaxSize().testTag("competition_mahjong_challenge_screen")) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            val question: @Composable (Modifier) -> Unit = { panel ->
                Column(panel.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("国标麻将 · 牌型训练", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = onBack, enabled = !state.loading) { Text("返回") }
                    }
                    Text("已完成 ${state.completedIds.size}/${CompetitionMahjongChallengeLibrary.challenges.size}")
                    val rewards = competitionChallengeRewards(state.completedIds)
                    Text("训练奖励 ${rewards.stars} 星 · ${rewards.score} 分",
                        modifier = Modifier.testTag("competition_mahjong_challenge_rewards"))
                    Text(state.mode.label + if (state.mode == CompetitionMahjongChallengeMode.DAILY)
                        " · ${state.dailyDate}" else "", modifier = Modifier.testTag("competition_mahjong_challenge_mode"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { catalogOpen = true }, enabled = state.canOperate,
                            modifier = Modifier.testTag("competition_mahjong_challenge_catalog")) { Text("题目目录") }
                        OutlinedButton(onClick = onDaily, enabled = state.canOperate,
                            modifier = Modifier.testTag("competition_mahjong_challenge_daily")) { Text("每日牌型") }
                        OutlinedButton(onClick = { onRandom(null, null) }, enabled = state.canOperate,
                            modifier = Modifier.testTag("competition_mahjong_challenge_random")) { Text("随机挑战") }
                    }
                    if (exercise != null) {
                        Text(exercise.challenge.title, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("competition_mahjong_challenge_title"))
                        Text("${exercise.challenge.level.label} · ${exercise.challenge.theme.label}")
                        Text(exercise.challenge.prompt,
                            modifier = Modifier.testTag("competition_mahjong_challenge_prompt"))
                        if (exercise.visibleHand.isNotEmpty()) {
                            FlowRow(Modifier.testTag("competition_mahjong_challenge_hand"),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                exercise.visibleHand.forEach { MahjongTile(it, faceUp = true) }
                            }
                        }
                        exercise.drawnTile?.let { tile -> Text("本次摸牌 ${competitionMahjongTileName(tile)}") }
                        Text("规则索引：官方番种 ID " + exercise.challenge.sourceFanIds.sorted().joinToString("、"),
                            style = MaterialTheme.typography.bodySmall)
                    } else Text(if (state.loading) "正在读取牌型题…" else "牌型题暂不可用")
                }
            }
            val answers: @Composable (Modifier) -> Unit = { panel ->
                Column(panel.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (exercise != null) {
                        if (exercise.discardTraining && !exercise.completed) {
                            if (exercise.chosenDiscard == null) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    exercise.visibleHand.distinct().forEach { tile ->
                                        OutlinedButton(onClick = { onDiscard(tile) }, enabled = state.canOperate,
                                            modifier = Modifier.testTag("competition_mahjong_challenge_discard_$tile")) {
                                            Text("打出${competitionMahjongTileName(tile)}")
                                        }
                                    }
                                }
                            } else Button(onClick = onConfirmWin, enabled = state.canOperate,
                                modifier = Modifier.testTag("competition_mahjong_challenge_confirm_win")) { Text("自摸") }
                        }
                        exercise.challenge.choices.forEach { choice ->
                            val selected = exercise.selectedChoiceId == choice.id
                            if (selected) Button(onClick = { onAnswer(choice.id) },
                                enabled = state.canOperate && !exercise.completed,
                                modifier = Modifier.fillMaxWidth().testTag("competition_mahjong_challenge_choice_${choice.id}")) {
                                Text(choice.text)
                            } else OutlinedButton(onClick = { onAnswer(choice.id) },
                                enabled = state.canOperate && !exercise.completed,
                                modifier = Modifier.fillMaxWidth().testTag("competition_mahjong_challenge_choice_${choice.id}")) {
                                Text(choice.text)
                            }
                        }
                        Text(exercise.feedback, modifier = Modifier.testTag("competition_mahjong_challenge_feedback"),
                            color = if (exercise.completed) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                        Text("尝试 ${exercise.attempts} 次")
                        if (exercise.completed && !state.needsSave) {
                            val index = CompetitionMahjongChallengeLibrary.challenges.indexOfFirst {
                                it.id == exercise.challenge.id }
                            Text(if (index < CompetitionMahjongChallengeLibrary.challenges.lastIndex) {
                                "本题完成，下一道主线题已解锁。"
                            } else "本题完成，主线训练已通关。", color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("competition_mahjong_challenge_completed"))
                            if (state.mode == CompetitionMahjongChallengeMode.MAINLINE &&
                                index < CompetitionMahjongChallengeLibrary.challenges.lastIndex) {
                                Button(onClick = { onSelect(CompetitionMahjongChallengeLibrary.challenges[index + 1].id) },
                                    enabled = state.canOperate && state.isUnlocked(
                                        CompetitionMahjongChallengeLibrary.challenges[index + 1].id),
                                    modifier = Modifier.testTag("competition_mahjong_challenge_next")) { Text("下一题") }
                            }
                        }
                        if (state.needsSave) Text("答案正确，完成进度尚未保存。",
                            modifier = Modifier.testTag("competition_mahjong_challenge_pending_save"))
                        OutlinedButton(onClick = onReset, enabled = state.canOperate && !exercise.completed) { Text("重置本题") }
                    }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("competition_mahjong_challenge_error"))
                        Button(onClick = onRetry, enabled = !state.loading,
                            modifier = Modifier.testTag("competition_mahjong_challenge_retry")) { Text("重试") }
                    }
                    Text("首次答对记录完成并解锁下一题；重复练习不会重复计完成。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (maxWidth >= 650.dp) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                question(Modifier.weight(1f).fillMaxHeight())
                answers(Modifier.width(360.dp).fillMaxHeight())
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                question(Modifier.fillMaxWidth().weight(1f))
                answers(Modifier.fillMaxWidth().heightIn(max = 320.dp))
            }
        }
    }
    if (catalogOpen) AlertDialog(
        onDismissRequest = { catalogOpen = false },
        modifier = Modifier.testTag("competition_mahjong_challenge_catalog_dialog"),
        title = { Text("国标麻将牌型训练") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(theme == null, { theme = null }, label = { Text("全部主题") })
                    CompetitionMahjongChallengeTheme.entries.forEach { option ->
                        FilterChip(theme == option, { theme = option }, label = { Text(option.label) },
                            modifier = Modifier.testTag("competition_mahjong_challenge_theme_${option.name}"))
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(level == null, { level = null }, label = { Text("全部难度") })
                    CompetitionMahjongChallengeLevel.entries.forEach { option ->
                        FilterChip(level == option, { level = option }, label = { Text(option.label) },
                            modifier = Modifier.testTag("competition_mahjong_challenge_level_${option.name}"))
                    }
                }
                val filtered = CompetitionMahjongChallenges.catalog(theme, level)
                val hasUnlocked = filtered.any { state.isUnlocked(it.id) }
                OutlinedButton(onClick = { catalogOpen = false; onRandom(theme, level) },
                    enabled = state.canOperate && hasUnlocked,
                    modifier = Modifier.testTag("competition_mahjong_challenge_filtered_random")) {
                    Text("按筛选随机")
                }
                if (!hasUnlocked && filtered.isNotEmpty()) Text("先完成前置主线题，再挑战这些题目。")
                filtered.forEachIndexed { index, challenge ->
                    val unlocked = state.isUnlocked(challenge.id)
                    TextButton(onClick = {
                        catalogOpen = false
                        if (theme != null || level != null) onThemedSelect(challenge.id) else onSelect(challenge.id)
                    }, enabled = state.canOperate && unlocked,
                        modifier = Modifier.testTag("competition_mahjong_challenge_item_${challenge.id}")) {
                        Text("${CompetitionMahjongChallengeLibrary.challenges.indexOf(challenge) + 1}. ${challenge.title} · " +
                            challenge.level.label + if (challenge.id in state.completedIds) " · 已完成"
                            else if (!unlocked) " · 未解锁" else "")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { catalogOpen = false }) { Text("关闭") } },
    )
}
