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
        const val CURRENT_VERSION = 4
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
        require(lines.size >= 4)
        require(lines[0] == "MOCS-XQ-ENDGAMES|${ChineseChessEndgamePack.CURRENT_VERSION}")
        val license = lines[1].headerField("LICENSE")
        val author = lines[2].headerField("AUTHOR")
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
                    require(active.moves.isEmpty()) { "Pieces must precede moves" }
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

                "MOCS-XQ-ENDGAMES", "LICENSE", "AUTHOR" ->
                    error("Pack headers must appear exactly once at the start")
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

    private fun String.headerField(name: String): String =
        split('|').also { require(it.size == 2 && it[0] == name) }[1].boundedText()

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
            validateReachablePlacement()
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

        private fun validateReachablePlacement() {
            val red = ChineseChessSide.RED
            val black = ChineseChessSide.BLACK
            val limits = mapOf(
                ChineseChessPieceType.GENERAL to 1,
                ChineseChessPieceType.ADVISOR to 2,
                ChineseChessPieceType.ELEPHANT to 2,
                ChineseChessPieceType.HORSE to 2,
                ChineseChessPieceType.CHARIOT to 2,
                ChineseChessPieceType.CANNON to 2,
                ChineseChessPieceType.SOLDIER to 5,
            )
            ChineseChessSide.entries.forEach { side ->
                pieces.filter { it.piece.side == side }
                    .groupingBy { it.piece.type }
                    .eachCount()
                    .forEach { (type, count) -> require(count <= requireNotNull(limits[type])) }
            }
            pieces.forEach { positioned ->
                val (x, y) = positioned.position
                val side = positioned.piece.side
                // These fixed-square checks are necessary, not proof that the full game is reachable.
                val inPalace = x in 3..5 && y in if (side == red) 7..9 else 0..2
                when (positioned.piece.type) {
                    ChineseChessPieceType.GENERAL ->
                        require(inPalace) { "General outside palace" }
                    ChineseChessPieceType.ADVISOR -> {
                        require(inPalace) { "Advisor outside palace" }
                        val homeRow = if (side == red) 9 else 0
                        val farRow = if (side == red) 7 else 2
                        val centerRow = if (side == red) 8 else 1
                        require(((y == homeRow || y == farRow) && x in listOf(3, 5)) ||
                            (y == centerRow && x == 4)) { "Advisor cannot reach square" }
                    }
                    ChineseChessPieceType.ELEPHANT -> {
                        require(if (side == red) y >= 5 else y <= 4) {
                            "Elephant crossed the river"
                        }
                        val homeRow = if (side == red) 9 else 0
                        val centerRow = if (side == red) 7 else 2
                        val farRow = if (side == red) 5 else 4
                        require((y == homeRow && x in listOf(2, 6)) ||
                            (y == centerRow && x in listOf(0, 4, 8)) ||
                            (y == farRow && x in listOf(2, 6))) {
                            "Elephant cannot reach square"
                        }
                    }
                    ChineseChessPieceType.SOLDIER -> {
                        require(if (side == red) y <= 6 else y >= 3) {
                            "Soldier behind starting row"
                        }
                        require(!(if (side == red) y >= 5 else y <= 4) || x % 2 == 0) {
                            "Uncrossed soldier cannot reach file"
                        }
                    }
                    else -> Unit
                }
            }
            val redGeneral = pieces.single {
                it.piece.side == red && it.piece.type == ChineseChessPieceType.GENERAL
            }.position
            val blackGeneral = pieces.single {
                it.piece.side == black && it.piece.type == ChineseChessPieceType.GENERAL
            }.position
            require(redGeneral.x != blackGeneral.x || pieces.any {
                it.position.x == redGeneral.x &&
                    it.position.y in (blackGeneral.y + 1) until redGeneral.y
            }) { "Generals face each other" }
        }
    }

    private const val ASSET_PATH = "endgames/chinese_chess/endgames-v4.txt"
    private const val MAX_PACK_BYTES = 2 * 1024 * 1024
    private const val MAX_LEVELS_PER_CHAPTER = 3_000
    private const val MAX_PLAYER_MOVES = 100
    private const val MAX_REWARD = 5
    private const val MAX_SCORE_REWARD = 1_000
    private const val MAX_TEXT_LENGTH = 80
}
