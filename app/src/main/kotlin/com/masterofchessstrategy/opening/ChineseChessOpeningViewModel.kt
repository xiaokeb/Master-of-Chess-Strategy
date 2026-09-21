package com.masterofchessstrategy.opening

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ChineseChessOpeningFrame(
    val board: List<ChineseChessPiece?>,
    val sideToMove: ChineseChessSide,
    val stepTitle: String,
    val explanation: String,
)

internal data class PreparedOpeningAutoPlay(
    val openingId: String,
    val difficulty: Difficulty,
    val initialState: ByteArray,
) {
    init {
        require(openingId.isNotBlank())
        require(initialState.isNotEmpty())
    }
}

internal data class ChineseChessOpeningUiState(
    val lines: List<ChineseChessOpeningLine> = ChineseChessOpeningLibrary.lines,
    val selectedLine: ChineseChessOpeningLine? = lines.firstOrNull(),
    val frames: List<ChineseChessOpeningFrame> = emptyList(),
    val frameIndex: Int = 0,
    val speed: Float = 1f,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = true,
    val isIncompatible: Boolean = false,
    val difficulty: Difficulty = Difficulty.EASY,
    val unlockedDifficulties: Set<Difficulty> = emptySet(),
    val endpointState: ByteArray? = null,
) {
    val currentFrame: ChineseChessOpeningFrame?
        get() = frames.getOrNull(frameIndex)
}

internal class ChineseChessOpeningViewModel(
    unlockedDifficulties: Set<Difficulty>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val engineFactory: () -> ChineseChessRuleEngine,
) : ViewModel() {
    var uiState by mutableStateOf(
        ChineseChessOpeningUiState(
            difficulty = unlockedDifficulties.firstOrNull() ?: Difficulty.EASY,
            unlockedDifficulties = unlockedDifficulties.toSet(),
        ),
    )
        private set

    private var loadJob: Job? = null
    private var playbackJob: Job? = null

    init {
        uiState.selectedLine?.let(::loadLine)
    }

    fun selectLine(id: String) {
        val line = ChineseChessOpeningLibrary.find(id) ?: return
        if (line.id == uiState.selectedLine?.id) return
        stopPlayback()
        loadLine(line)
    }

    fun selectDifficulty(difficulty: Difficulty) {
        if (difficulty !in uiState.unlockedDifficulties) return
        uiState = uiState.copy(difficulty = difficulty)
    }

    fun previous() {
        stopPlayback()
        uiState = uiState.copy(frameIndex = (uiState.frameIndex - 1).coerceAtLeast(0))
    }

    fun next() {
        stopPlayback()
        uiState = uiState.copy(
            frameIndex = (uiState.frameIndex + 1)
                .coerceAtMost(uiState.frames.lastIndex.coerceAtLeast(0)),
        )
    }

    fun setSpeed(speed: Float) {
        require(speed in MIN_SPEED..MAX_SPEED)
        val resume = uiState.isPlaying
        stopPlayback()
        uiState = uiState.copy(speed = speed)
        if (resume) togglePlayback()
    }

    fun togglePlayback() {
        if (uiState.frames.size < 2) return
        if (playbackJob?.isActive == true) {
            stopPlayback()
            return
        }
        if (uiState.frameIndex >= uiState.frames.lastIndex) {
            uiState = uiState.copy(frameIndex = 0)
        }
        uiState = uiState.copy(isPlaying = true)
        playbackJob = viewModelScope.launch {
            while (uiState.frameIndex < uiState.frames.lastIndex) {
                delay((BASE_FRAME_DELAY_MILLIS / uiState.speed).toLong())
                uiState = uiState.copy(frameIndex = uiState.frameIndex + 1)
            }
            uiState = uiState.copy(isPlaying = false)
            playbackJob = null
        }
    }

    fun prepareAutoPlay(): PreparedOpeningAutoPlay? {
        val line = uiState.selectedLine ?: return null
        val state = uiState.endpointState ?: return null
        if (uiState.difficulty !in uiState.unlockedDifficulties) return null
        return PreparedOpeningAutoPlay(line.id, uiState.difficulty, state.copyOf())
    }

    private fun loadLine(line: ChineseChessOpeningLine) {
        loadJob?.cancel()
        uiState = uiState.copy(
            selectedLine = line,
            frames = emptyList(),
            frameIndex = 0,
            isLoading = true,
            isIncompatible = false,
            endpointState = null,
        )
        loadJob = viewModelScope.launch {
            val decoded = try {
                withContext(dispatcher) { decode(line) }
            } catch (_: CancellationException) {
                return@launch
            } catch (_: RuntimeException) {
                null
            } catch (_: LinkageError) {
                null
            }
            uiState = if (decoded == null) {
                uiState.copy(isLoading = false, isIncompatible = true)
            } else {
                uiState.copy(
                    frames = decoded.first,
                    endpointState = decoded.second,
                    isLoading = false,
                )
            }
        }
    }

    private fun decode(
        line: ChineseChessOpeningLine,
    ): Pair<List<ChineseChessOpeningFrame>, ByteArray>? {
        val engine = engineFactory()
        return try {
            engine.reset()
            val frames = mutableListOf(
                engine.captureFrame("标准初始局面", "观察双方完整阵形，再按步骤理解出子目的。"),
            )
            line.steps.forEach { step ->
                if (engine.apply(step.move) !is ActionResult.Accepted) return null
                if (engine.gameResult() != GameResult.ONGOING) return null
                frames += engine.captureFrame(step.title, step.explanation)
            }
            frames to engine.serialize().copyOf()
        } finally {
            engine.close()
        }
    }

    private fun ChineseChessRuleEngine.captureFrame(
        title: String,
        explanation: String,
    ): ChineseChessOpeningFrame {
        val side = ChineseChessSide.entries.firstOrNull { it.code == currentPlayer.value }
            ?: error("Unsupported Chinese chess player")
        val board = buildList(ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT) {
            repeat(ChineseChessBoard.HEIGHT) { y ->
                repeat(ChineseChessBoard.WIDTH) { x ->
                    add(pieceAt(BoardPosition(x, y)))
                }
            }
        }
        return ChineseChessOpeningFrame(board, side, title, explanation)
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        uiState = uiState.copy(isPlaying = false)
    }

    override fun onCleared() {
        loadJob?.cancel()
        stopPlayback()
    }

    companion object {
        private const val BASE_FRAME_DELAY_MILLIS = 1_000L
        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 4f

        fun factory(
            unlockedDifficulties: Set<Difficulty>,
            engineFactory: () -> ChineseChessRuleEngine,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChineseChessOpeningViewModel(
                    unlockedDifficulties = unlockedDifficulties,
                    engineFactory = engineFactory,
                )
            }
        }
    }
}
