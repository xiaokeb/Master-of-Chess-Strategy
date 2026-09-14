package com.masterofchessstrategy.records

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameRecordRepository
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ChineseChessReplayFrame(
    val board: List<ChineseChessPiece?>,
    val sideToMove: ChineseChessSide,
)

internal data class ChineseChessReplayUiState(
    val record: GameRecord? = null,
    val frames: List<ChineseChessReplayFrame> = emptyList(),
    val frameIndex: Int = 0,
    val speed: Float = 1f,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = true,
    val isIncompatible: Boolean = false,
) {
    val currentFrame: ChineseChessReplayFrame?
        get() = frames.getOrNull(frameIndex)
}

internal class ChineseChessReplayViewModel(
    private val recordId: String,
    private val repository: GameRecordRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val engineFactory: () -> ChineseChessRuleEngine,
) : ViewModel() {
    var uiState by mutableStateOf(ChineseChessReplayUiState())
        private set

    private var playbackJob: Job? = null

    init {
        load()
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

    fun jumpToStart() {
        stopPlayback()
        uiState = uiState.copy(frameIndex = 0)
    }

    fun jumpToEnd() {
        stopPlayback()
        uiState = uiState.copy(frameIndex = uiState.frames.lastIndex.coerceAtLeast(0))
    }

    fun setSpeed(speed: Float) {
        require(speed in MIN_SPEED..MAX_SPEED)
        val wasPlaying = uiState.isPlaying
        stopPlayback()
        uiState = uiState.copy(speed = speed)
        if (wasPlaying) togglePlayback()
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

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        uiState = uiState.copy(isPlaying = false)
    }

    private fun load() {
        viewModelScope.launch {
            val record = try {
                repository.load(recordId)
            } catch (_: RuntimeException) {
                null
            }
            if (record == null || record.gameType != GameType.CHINESE_CHESS) {
                uiState = ChineseChessReplayUiState(isLoading = false, isIncompatible = true)
                return@launch
            }
            val frames = try {
                withContext(dispatcher) { decodeFrames(record) }
            } catch (_: CancellationException) {
                return@launch
            } catch (_: RuntimeException) {
                null
            } catch (_: LinkageError) {
                null
            }
            uiState = if (frames == null) {
                ChineseChessReplayUiState(
                    record = record,
                    isLoading = false,
                    isIncompatible = true,
                )
            } else {
                ChineseChessReplayUiState(
                    record = record,
                    frames = frames,
                    isLoading = false,
                )
            }
        }
    }

    private fun decodeFrames(record: GameRecord): List<ChineseChessReplayFrame>? {
        val engine = engineFactory()
        return try {
            if (engine.restore(record.engineState) !is RestoreResult.Restored) return null
            val reversed = mutableListOf<ChineseChessReplayFrame>()
            reversed += engine.captureFrame()
            while (engine.undo()) {
                if (reversed.size > GameRecord.MAX_MOVE_COUNT) return null
                reversed += engine.captureFrame()
            }
            if (reversed.size != record.moveCount + 1) return null
            reversed.asReversed()
        } finally {
            engine.close()
        }
    }

    private fun ChineseChessRuleEngine.captureFrame(): ChineseChessReplayFrame {
        val side = ChineseChessSide.entries.firstOrNull { it.code == currentPlayer.value }
            ?: error("Unsupported Chinese chess player")
        val board = buildList(ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT) {
            repeat(ChineseChessBoard.HEIGHT) { y ->
                repeat(ChineseChessBoard.WIDTH) { x ->
                    add(pieceAt(BoardPosition(x, y)))
                }
            }
        }
        return ChineseChessReplayFrame(board, side)
    }

    override fun onCleared() {
        stopPlayback()
    }

    companion object {
        private const val BASE_FRAME_DELAY_MILLIS = 1_000L
        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 4f

        fun factory(
            recordId: String,
            repository: GameRecordRepository,
            engineFactory: () -> ChineseChessRuleEngine,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ChineseChessReplayViewModel(
                        recordId = recordId,
                        repository = repository,
                        engineFactory = engineFactory,
                    )
                }
            }
    }
}
