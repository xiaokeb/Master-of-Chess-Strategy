package com.masterofchessstrategy.endgame

import android.content.res.AssetManager
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessPositionCodec
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.PositionedChineseChessPiece

internal enum class ChineseChessEndgameTrack { MAIN, BONUS }

internal data class ChineseChessEndgameLevel(
    val id: String,
    val difficulty: Difficulty,
    val chapterOrder: Int,
    val title: String,
    val theme: String,
    val sideToMove: ChineseChessSide,
    val maxPlayerMoves: Int,
    val starReward: Int,
    val scoreReward: Int,
    val pieces: List<PositionedChineseChessPiece>,
    val principalVariation: List<BoardMove>,
    val track: ChineseChessEndgameTrack = ChineseChessEndgameTrack.MAIN,
) {
    val initialEngineState: ByteArray
        get() = ChineseChessPositionCodec.encode(sideToMove, pieces)
}

internal data class ChineseChessEndgamePack(
    val version: Int,
    val license: String,
    val author: String,
    val levels: List<ChineseChessEndgameLevel>,
) {
    fun sessionVariantId(levelId: String): String = "$levelId@v$version"

    init {
        require(version == CURRENT_VERSION)
        require(license == REQUIRED_LICENSE)
        require(author.isNotBlank())
        require(levels.isNotEmpty())
        require(levels.map(ChineseChessEndgameLevel::id).distinct().size == levels.size)
        Difficulty.entries.forEach { difficulty ->
            val chapter = levels.filter { it.difficulty == difficulty }
                .sortedBy(ChineseChessEndgameLevel::chapterOrder)
            val orders = chapter.map(ChineseChessEndgameLevel::chapterOrder)
            require(orders == (1..orders.size).toList()) {
                "Level order must be contiguous within each difficulty"
            }
            require(chapter.firstOrNull()?.track != ChineseChessEndgameTrack.BONUS) {
                "Bonus levels require a main progression"
            }
            require(
                chapter.dropWhile { it.track == ChineseChessEndgameTrack.MAIN }
                    .all { it.track == ChineseChessEndgameTrack.BONUS },
            ) { "Bonus levels must follow the main progression" }
        }
    }

    companion object {
        const val CURRENT_VERSION = 3
        const val REQUIRED_LICENSE = "GPL-3.0-or-later"
    }
}

internal object ChineseChessEndgamePackParser {
    fun loadBundled(assets: AssetManager): ChineseChessEndgamePack =
        assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use {
            parse(it.readText())
        }

    fun parse(content: String): ChineseChessEndgamePack {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_PACK_BYTES)
        val lines = content.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
        require(lines.firstOrNull() == "MOCS-XQ-ENDGAMES|${ChineseChessEndgamePack.CURRENT_VERSION}")
        val license = lines.singleField("LICENSE")
        val author = lines.singleField("AUTHOR")
        val levels = mutableListOf<ChineseChessEndgameLevel>()
        var builder: LevelBuilder? = null
        lines.drop(3).forEach { line ->
            val fields = line.split('|')
            when (fields.firstOrNull()) {
                "LEVEL" -> {
                    require(builder == null && fields.size == 12)
                    builder = LevelBuilder(
                        id = fields[1],
                        difficulty = fields[2].toDifficulty(),
                        chapterOrder = fields[3].boundedInt(1, MAX_LEVELS_PER_CHAPTER),
                        title = fields[4].boundedText(),
                        theme = fields[5].boundedText(),
                        sideToMove = fields[6].toSide(),
                        maxPlayerMoves = fields[7].boundedInt(1, MAX_PLAYER_MOVES),
                        starReward = fields[8].boundedInt(1, MAX_REWARD),
                        scoreReward = fields[9].boundedInt(1, MAX_SCORE_REWARD),
                        declaredPieceCount = fields[10].boundedInt(2, 32),
                        track = fields[11].toTrack(),
                    )
                }

                "PIECE" -> {
                    require(fields.size == 5)
                    val active = requireNotNull(builder)
                    active.pieces += PositionedChineseChessPiece(
                        position = BoardPosition(
                            fields[1].boundedInt(0, 8),
                            fields[2].boundedInt(0, 9),
                        ),
                        piece = ChineseChessPiece(fields[4].toPieceType(), fields[3].toSide()),
                    )
                }

                "MOVE" -> {
                    require(fields.size == 5)
                    requireNotNull(builder).moves += BoardMove(
                        BoardPosition(fields[1].boundedInt(0, 8), fields[2].boundedInt(0, 9)),
                        BoardPosition(fields[3].boundedInt(0, 8), fields[4].boundedInt(0, 9)),
                    )
                }

                "END" -> {
                    require(fields.size == 1)
                    levels += requireNotNull(builder).build()
                    builder = null
                }

                "MOCS-XQ-ENDGAMES", "LICENSE", "AUTHOR" -> Unit
                else -> error("Unknown endgame pack record")
            }
        }
        require(builder == null)
        return ChineseChessEndgamePack(
            version = ChineseChessEndgamePack.CURRENT_VERSION,
            license = license,
            author = author,
            levels = levels,
        )
    }

    private fun List<String>.singleField(name: String): String {
        val matches = filter { it.startsWith("$name|") }
        require(matches.size == 1)
        return matches.single().substringAfter('|').boundedText()
    }

    private fun String.boundedText(): String =
        trim().also { require(it.isNotBlank() && it.length <= MAX_TEXT_LENGTH && '|' !in it) }

    private fun String.boundedInt(minimum: Int, maximum: Int): Int =
        toInt().also { require(it in minimum..maximum) }

    private fun String.toDifficulty(): Difficulty =
        Difficulty.entries.firstOrNull { it.code == boundedInt(0, 3) }
            ?: error("Unsupported difficulty")

    private fun String.toSide(): ChineseChessSide =
        ChineseChessSide.entries.firstOrNull { it.name == this }
            ?: error("Unsupported side")

    private fun String.toPieceType(): ChineseChessPieceType =
        ChineseChessPieceType.entries.firstOrNull { it.name == this }
            ?: error("Unsupported piece type")

    private fun String.toTrack(): ChineseChessEndgameTrack =
        ChineseChessEndgameTrack.entries.firstOrNull { it.name == this }
            ?: error("Unsupported level track")

    private data class LevelBuilder(
        val id: String,
        val difficulty: Difficulty,
        val chapterOrder: Int,
        val title: String,
        val theme: String,
        val sideToMove: ChineseChessSide,
        val maxPlayerMoves: Int,
        val starReward: Int,
        val scoreReward: Int,
        val declaredPieceCount: Int,
        val track: ChineseChessEndgameTrack,
        val pieces: MutableList<PositionedChineseChessPiece> = mutableListOf(),
        val moves: MutableList<BoardMove> = mutableListOf(),
    ) {
        fun build(): ChineseChessEndgameLevel {
            val difficultyName = difficulty.name.lowercase()
            require(id.matches(Regex("xq-$difficultyName-[0-9]{3,4}")))
            require(id.substringAfterLast('-').toInt() == chapterOrder) {
                "Level id suffix must equal its chapter order"
            }
            require(sideToMove == ChineseChessSide.RED) {
                "Endgame pack supports red-to-move puzzles"
            }
            require(pieces.size == declaredPieceCount)
            require(moves.isNotEmpty() && moves.size <= maxPlayerMoves * 2 - 1)
            // Also validates duplicate squares, coordinates, and both generals.
            ChineseChessPositionCodec.encode(sideToMove, pieces)
            return ChineseChessEndgameLevel(
                id = id,
                difficulty = difficulty,
                chapterOrder = chapterOrder,
                title = title,
                theme = theme,
                sideToMove = sideToMove,
                maxPlayerMoves = maxPlayerMoves,
                starReward = starReward,
                scoreReward = scoreReward,
                pieces = pieces.toList(),
                principalVariation = moves.toList(),
                track = track,
            )
        }
    }

    private const val ASSET_PATH = "endgames/chinese_chess/endgames-v3.txt"
    private const val MAX_PACK_BYTES = 2 * 1024 * 1024
    private const val MAX_LEVELS_PER_CHAPTER = 3_000
    private const val MAX_PLAYER_MOVES = 100
    private const val MAX_REWARD = 5
    private const val MAX_SCORE_REWARD = 1_000
    private const val MAX_TEXT_LENGTH = 80
}
