package com.masterofchessstrategy.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.ChineseChessFenCodec
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import java.util.zip.CRC32

/** ADB-only rule inspection: formal controls, real engine, RAM-only saves. */
class XiangqiRuleRevisionVisualCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = ChineseChessFenCodec.parse(CYCLE_FEN).toEngineState()
        val saved = NativeChineseChessEngine().use { engine ->
            check(engine.restore(initial) == RestoreResult.Restored)
            val current = engine.serialize()
            if (intent.getBooleanExtra(EXTRA_LEGACY_RULES, false)) asLegacy(current) else current
        }
        val repository = MemorySessionRepository(
            GameSessionSnapshot(
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.LOCAL_TWO_PLAYER,
                difficulty = null,
                engineState = saved,
                updatedAtEpochMillis = 0,
                sessionId = "qa-rule-revision",
            ),
        )
        val factory = viewModelFactory {
            initializer {
                ChineseChessGameViewModel(
                    sessionRepository = repository,
                    // Restart imports a fresh position, never pins the old revision.
                    initialPositionState = initial,
                    clockTickIntervalMillis = null,
                    engineFactory = { NativeChineseChessEngine() },
                )
            }
        }
        setContent {
            val game: ChineseChessGameViewModel = viewModel(factory = factory)
            ChineseChessGameScreen(
                state = game.uiState,
                onSquareTap = game::onSquareTap,
                onUndo = game::undo,
                onHint = game::requestHint,
                onResign = game::resign,
                onRestart = { game.restartWithAiFirst(false) },
                onBack = ::finish,
                onDraw = game::offerOrAcceptDraw,
                onNaturalLimitClaim = game::claimNaturalLimit,
                onRetrySave = game::retryTerminalSave,
            )
        }
    }

    /** Simulates restored bytes without touching Room, rewards or user settings. */
    private class MemorySessionRepository(initial: GameSessionSnapshot) : GameSessionRepository {
        private var saved: GameSessionSnapshot? = initial.defensiveCopy()

        override suspend fun load(gameType: GameType): LoadGameSessionResult =
            saved?.takeIf { it.gameType == gameType }?.let {
                LoadGameSessionResult.Loaded(it.defensiveCopy())
            } ?: LoadGameSessionResult.NotFound

        override suspend fun save(snapshot: GameSessionSnapshot) {
            saved = snapshot.defensiveCopy()
        }

        override suspend fun clear(gameType: GameType) {
            if (saved?.gameType == gameType) saved = null
        }
    }

    private fun asLegacy(current: ByteArray): ByteArray {
        check(current[4].toInt() == 4 && current[current.size - 5].toInt() == 1)
        val legacy = current.copyOf(current.size - 1)
        legacy[4] = 3
        val crc = CRC32().apply { update(legacy, 0, legacy.size - 4) }.value
        repeat(4) { byte -> legacy[legacy.size - 4 + byte] = (crc ushr (8 * byte)).toByte() }
        return legacy
    }

    companion object {
        const val EXTRA_LEGACY_RULES = "legacy_rules"
        private const val CYCLE_FEN = "2b6/4k4/3a5/2PC2C2/3NPN3/9/9/5K3/9/9 w - - 0 1"
    }
}
