package com.masterofchessstrategy.endgame

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.CompleteEndgameResult
import com.masterofchessstrategy.data.CompletedEndgameLevel
import com.masterofchessstrategy.data.EndgameProgress
import com.masterofchessstrategy.data.EndgameProgressRepository
import com.masterofchessstrategy.data.LoadEndgameProgressResult
import com.masterofchessstrategy.engine.Difficulty
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.random.Random

internal enum class EndgameCatalogMode {
    MAIN,
    THEME,
    DAILY,
    RANDOM,
}

internal enum class EndgameCatalogFeedback {
    LOAD_INCOMPATIBLE,
    SAVE_FAILED,
    FIRST_COMPLETION,
    BEST_MOVES_IMPROVED,
    COMPLETED_AGAIN,
}

internal data class EndgameLevelEntry(
    val level: ChineseChessEndgameLevel,
    val isChapterUnlocked: Boolean,
    val isUnlocked: Boolean,
    val completion: CompletedEndgameLevel?,
) {
    val isCompleted: Boolean = completion != null
}

internal data class ChineseChessEndgameUiState(
    val mode: EndgameCatalogMode = EndgameCatalogMode.MAIN,
    val selectedDifficulty: Difficulty = Difficulty.EASY,
    val selectedTheme: String? = null,
    val entries: List<EndgameLevelEntry> = emptyList(),
    val visibleEntries: List<EndgameLevelEntry> = emptyList(),
    val themes: List<String> = emptyList(),
    val progress: EndgameProgress = EndgameProgress(),
    val isLoading: Boolean = true,
    val feedback: EndgameCatalogFeedback? = null,
) {
    val completedCount: Int = progress.completedById.size
}

/** Coordinates the offline catalog, linear unlocks, daily choice and rewards. */
internal class ChineseChessEndgameViewModel(
    private val pack: ChineseChessEndgamePack,
    private val repository: EndgameProgressRepository,
    private val localDayCode: () -> Long = ::currentLocalDayCode,
    private val randomIndex: (Int) -> Int = { Random.nextInt(it) },
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private var randomLevelId: String? = null

    var uiState by mutableStateOf(
        ChineseChessEndgameUiState(themes = pack.levels.map { it.theme }.distinct().sorted()),
    )
        private set

    init {
        reload()
    }

    fun selectMode(mode: EndgameCatalogMode) {
        if (mode == uiState.mode && mode != EndgameCatalogMode.RANDOM) return
        if (mode == EndgameCatalogMode.RANDOM) randomLevelId = chooseRandomLevelId(uiState.entries)
        publish(uiState.progress, mode = mode, feedback = null)
    }

    fun selectDifficulty(difficulty: Difficulty) {
        if (difficulty == uiState.selectedDifficulty) return
        randomLevelId = null
        publish(
            progress = uiState.progress,
            selectedDifficulty = difficulty,
            selectedTheme = null,
            feedback = null,
        )
    }

    fun selectTheme(theme: String) {
        require(theme in uiState.themes)
        publish(uiState.progress, selectedTheme = theme, feedback = null)
    }

    fun randomChallenge() {
        randomLevelId = chooseRandomLevelId(uiState.entries)
        publish(uiState.progress, mode = EndgameCatalogMode.RANDOM, feedback = null)
    }

    fun level(levelId: String): ChineseChessEndgameLevel? =
        pack.levels.firstOrNull { it.id == levelId }

    fun complete(levelId: String, playerMoves: Int) {
        val level = level(levelId) ?: return
        viewModelScope.launch {
            try {
                val result = repository.complete(level, playerMoves, nowEpochMillis())
                val loaded = repository.load()
                if (loaded is LoadEndgameProgressResult.Loaded) {
                    publish(
                        progress = loaded.progress,
                        feedback = result.toFeedback(),
                        isLoading = false,
                    )
                } else {
                    publish(
                        progress = EndgameProgress(),
                        feedback = EndgameCatalogFeedback.LOAD_INCOMPATIBLE,
                        isLoading = false,
                    )
                }
            } catch (_: RuntimeException) {
                uiState = uiState.copy(
                    isLoading = false,
                    feedback = EndgameCatalogFeedback.SAVE_FAILED,
                )
            }
        }
    }

    private fun reload() {
        viewModelScope.launch {
            try {
                when (val result = repository.load()) {
                    is LoadEndgameProgressResult.Loaded -> publish(
                        progress = result.progress,
                        isLoading = false,
                    )

                    LoadEndgameProgressResult.Incompatible -> publish(
                        progress = EndgameProgress(),
                        feedback = EndgameCatalogFeedback.LOAD_INCOMPATIBLE,
                        isLoading = false,
                    )
                }
            } catch (_: RuntimeException) {
                publish(
                    progress = EndgameProgress(),
                    feedback = EndgameCatalogFeedback.LOAD_INCOMPATIBLE,
                    isLoading = false,
                )
            }
        }
    }

    private fun publish(
        progress: EndgameProgress,
        mode: EndgameCatalogMode = uiState.mode,
        selectedDifficulty: Difficulty = uiState.selectedDifficulty,
        selectedTheme: String? = uiState.selectedTheme,
        feedback: EndgameCatalogFeedback? = uiState.feedback,
        isLoading: Boolean = uiState.isLoading,
    ) {
        val completed = progress.completedById
        val entries = pack.levels.map { level ->
            val earlierDifficulties = Difficulty.entries.take(level.difficulty.ordinal)
            val chapterUnlocked = earlierDifficulties.all { earlier ->
                pack.levels.filter { it.difficulty == earlier }
                    .all { it.id in completed }
            }
            val previousCompleted = level.chapterOrder == 1 || pack.levels.any {
                it.difficulty == level.difficulty &&
                    it.chapterOrder == level.chapterOrder - 1 &&
                    it.id in completed
            }
            EndgameLevelEntry(
                level = level,
                isChapterUnlocked = chapterUnlocked,
                isUnlocked = chapterUnlocked && previousCompleted,
                completion = completed[level.id],
            )
        }
        if (mode == EndgameCatalogMode.RANDOM && randomLevelId == null) {
            randomLevelId = chooseRandomLevelId(entries, selectedDifficulty)
        }
        val selectedEntries = entries.filter { it.level.difficulty == selectedDifficulty }
        val visible = when (mode) {
            EndgameCatalogMode.MAIN -> selectedEntries
            EndgameCatalogMode.THEME -> selectedEntries.filter {
                selectedTheme == null || it.level.theme == selectedTheme
            }
            EndgameCatalogMode.DAILY -> selectedEntries.dailyEntry()
            EndgameCatalogMode.RANDOM -> selectedEntries
                .filter { it.level.id == randomLevelId }
                .ifEmpty { selectedEntries.take(1) }
        }
        uiState = ChineseChessEndgameUiState(
            mode = mode,
            selectedDifficulty = selectedDifficulty,
            selectedTheme = selectedTheme,
            entries = entries,
            visibleEntries = visible,
            themes = selectedEntries.map { it.level.theme }.distinct().sorted(),
            progress = progress,
            isLoading = isLoading,
            feedback = feedback,
        )
    }

    private fun List<EndgameLevelEntry>.dailyEntry(): List<EndgameLevelEntry> {
        val candidates = filter(EndgameLevelEntry::isUnlocked)
        if (candidates.isEmpty()) return take(1)
        val index = Math.floorMod(
            localDayCode(),
            candidates.size.toLong(),
        ).toInt()
        return listOf(candidates[index])
    }

    private fun chooseRandomLevelId(
        entries: List<EndgameLevelEntry>,
        difficulty: Difficulty = uiState.selectedDifficulty,
    ): String? {
        val candidates = entries.filter {
            it.level.difficulty == difficulty && it.isUnlocked
        }
        if (candidates.isEmpty()) return null
        val index = randomIndex(candidates.size)
        require(index in candidates.indices) { "Random selector returned an invalid index" }
        return candidates[index].level.id
    }

    private fun CompleteEndgameResult.toFeedback(): EndgameCatalogFeedback = when {
        firstCompletion -> EndgameCatalogFeedback.FIRST_COMPLETION
        bestMovesImproved -> EndgameCatalogFeedback.BEST_MOVES_IMPROVED
        else -> EndgameCatalogFeedback.COMPLETED_AGAIN
    }

    companion object {
        private fun currentLocalDayCode(): Long = Calendar.getInstance().run {
            get(Calendar.YEAR) * 372L +
                get(Calendar.MONTH) * 31L +
                get(Calendar.DAY_OF_MONTH)
        }

        fun factory(
            pack: ChineseChessEndgamePack,
            repository: EndgameProgressRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ChineseChessEndgameViewModel(pack, repository) }
        }
    }
}
