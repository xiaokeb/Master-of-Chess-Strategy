package com.masterofchessstrategy.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import com.masterofchessstrategy.game.PikafishNetworkProvider

/** Direct, unsaved puzzle inspection in debug APKs; never changes progression. */
class XiangqiEndgameVisualCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val levelId = intent.getStringExtra(EXTRA_LEVEL_ID).orEmpty()
        val pack = ChineseChessEndgamePackParser.loadBundled(assets)
        val level = pack.levels.singleOrNull { it.id == levelId }
        if (level == null) {
            finish()
            return
        }
        val networkProvider = PikafishNetworkProvider(applicationContext)
        val factory = viewModelFactory {
            initializer {
                ChineseChessGameViewModel(
                    mode = StoredGameMode.ENDGAME,
                    difficulty = level.difficulty,
                    initialPositionState = level.initialEngineState,
                    sessionVariantId = pack.sessionVariantId(level.id),
                    endgameTitle = level.title,
                    endgameMaxPlayerMoves = level.maxPlayerMoves,
                    engineFactory = {
                        NativeChineseChessEngine(networkProvider::requireNetworkPath)
                    },
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
                onRetrySave = game::retryTerminalSave,
            )
        }
    }

    companion object {
        const val EXTRA_LEVEL_ID = "level_id"
    }
}
