package com.masterofchessstrategy.games.xiangqi

import com.masterofchessstrategy.data.CompletedGameSession
import com.masterofchessstrategy.endgame.ChineseChessEndgameLevel
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult

/** Validate a detached history, without changing the player's live engine. */
internal fun validateXiangqiEndgameCompletion(
    game: CompletedGameSession,
    level: ChineseChessEndgameLevel,
    engineFactory: () -> ChineseChessRuleEngine = { NativeChineseChessEngine() },
) {
    require(game.snapshot.gameType == GameType.CHINESE_CHESS) { "Wrong endgame family" }
    require(game.snapshot.difficulty == level.difficulty) { "Wrong endgame difficulty" }
    require(game.record.result == GameResult.FIRST_PLAYER_WIN) { "Endgame is not a red win" }
    val moves = game.record.moveCount
    require(moves > 0 && (moves + 1) / 2 <= level.maxPlayerMoves) { "Endgame exceeds move limit" }
    val initialPieces = level.pieces.associateBy { it.position }

    engineFactory().use { engine ->
        require(engine.gameType == GameType.CHINESE_CHESS) { "Wrong validation engine" }
        require(engine.restore(game.record.engineState) == RestoreResult.Restored) { "Invalid endgame history" }
        require(engine.gameResult() == GameResult.FIRST_PLAYER_WIN) { "Declared win is not a native win" }
        repeat(moves) {
            require(engine.undo()) { "Endgame move count exceeds history" }
        }
        require(!engine.undo()) { "Endgame history exceeds declared move count" }
        require(engine.currentPlayer.value == level.sideToMove.code && engine.gameResult() == GameResult.ONGOING) {
            "Wrong endgame starting turn or result"
        }
        for (y in 0 until ChineseChessBoard.HEIGHT) {
            for (x in 0 until ChineseChessBoard.WIDTH) {
                val point = BoardPosition(x, y)
                require(engine.pieceAt(point) == initialPieces[point]?.piece) { "History belongs to another endgame" }
            }
        }
    }
}
