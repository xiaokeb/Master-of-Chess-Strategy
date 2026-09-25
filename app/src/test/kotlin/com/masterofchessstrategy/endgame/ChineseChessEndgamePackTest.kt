package com.masterofchessstrategy.endgame

import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.Difficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChineseChessEndgamePackTest {
    @Test
    fun validPackParsesMetadataLevelsPiecesAndMoves() {
        val pack = ChineseChessEndgamePackParser.parse(VALID_PACK)

        assertEquals(4, pack.version)
        assertEquals("xq-easy-001@v4", pack.sessionVariantId("xq-easy-001"))
        assertEquals("GPL-3.0-or-later", pack.license)
        assertEquals(1, pack.levels.size)
        val level = pack.levels.single()
        assertEquals(Difficulty.EASY, level.difficulty)
        assertEquals(4, level.pieces.size)
        assertEquals(ChineseChessPieceType.CHARIOT, level.pieces.last().piece.type)
        assertEquals(1, level.principalVariation.size)
        assertEquals(106, level.initialEngineState.size)
    }

    @Test
    fun duplicateLevelAndNonContiguousOrderAreRejected() {
        val duplicate = VALID_PACK + VALID_LEVEL.substringAfter("LEVEL|").let { "LEVEL|$it" }
        assertThrows(IllegalArgumentException::class.java) {
            ChineseChessEndgamePackParser.parse(duplicate)
        }
        val wrongOrder = VALID_PACK.replace("|0|1|训练", "|0|2|训练")
        assertTrue(runCatching { ChineseChessEndgamePackParser.parse(wrongOrder) }.isFailure)
    }

    @Test
    fun mismatchedIdOrBlackToMoveIsRejected() {
        val mismatchedId = VALID_PACK.replace("xq-easy-001", "xq-hard-001")
        val blackToMove = VALID_PACK.replace("|RED|1|1|10|4", "|BLACK|1|1|10|4")

        assertTrue(runCatching { ChineseChessEndgamePackParser.parse(mismatchedId) }.isFailure)
        assertTrue(runCatching { ChineseChessEndgamePackParser.parse(blackToMove) }.isFailure)
    }

    @Test
    fun unknownTrackAndBonusWithoutMainAreRejected() {
        val unknownTrack = VALID_PACK.replace("|4|MAIN", "|4|UNKNOWN")
        val bonusWithoutMain = VALID_PACK.replace("|4|MAIN", "|4|BONUS")

        assertTrue(runCatching { ChineseChessEndgamePackParser.parse(unknownTrack) }.isFailure)
        assertTrue(runCatching { ChineseChessEndgamePackParser.parse(bonusWithoutMain) }.isFailure)
    }

    @Test
    fun bundledPackParsesEveryDifficultyAndUsesProjectLicense() {
        val content = File("src/main/assets/endgames/chinese_chess/endgames-v4.txt")
            .readText(Charsets.UTF_8)

        val pack = ChineseChessEndgamePackParser.parse(content)

        assertEquals("GPL-3.0-or-later", pack.license)
        assertEquals(Difficulty.entries.toSet(), pack.levels.map { it.difficulty }.toSet())
        assertEquals(10, pack.levels.size)
        assertEquals(5, pack.levels.count { it.theme == "唯一一步杀" })
        assertEquals(5, pack.levels.count { it.theme == "多解胜局" })
        assertEquals(5, pack.levels.count { it.track == ChineseChessEndgameTrack.MAIN })
        assertEquals(5, pack.levels.count { it.track == ChineseChessEndgameTrack.BONUS })
        assertTrue(pack.levels.all { it.principalVariation.isNotEmpty() })
    }

    @Test
    fun unreachablePiecePositionsAreRejected() {
        val redSoldierBehindStart = VALID_PACK.replace(
            "PIECE|4|5|RED|SOLDIER",
            "PIECE|4|7|RED|SOLDIER",
        )
        val blackSoldierBehindStart = VALID_PACK.replace(
            "PIECE|3|1|RED|CHARIOT",
            "PIECE|4|2|BLACK|SOLDIER",
        )
        val facingGenerals = VALID_PACK
            .replace("|10|4|MAIN", "|10|3|MAIN")
            .replace("PIECE|4|5|RED|SOLDIER", "")

        listOf(redSoldierBehindStart, blackSoldierBehindStart, facingGenerals).forEach {
            assertTrue(runCatching { ChineseChessEndgamePackParser.parse(it) }.isFailure)
        }
    }

    private companion object {
        const val VALID_LEVEL = """LEVEL|xq-easy-001|0|1|训练|一步杀|RED|1|1|10|4|MAIN
PIECE|4|9|RED|GENERAL
PIECE|4|0|BLACK|GENERAL
PIECE|4|5|RED|SOLDIER
PIECE|3|1|RED|CHARIOT
MOVE|3|1|4|1
END
"""
        const val VALID_PACK = """MOCS-XQ-ENDGAMES|4
LICENSE|GPL-3.0-or-later
AUTHOR|Test Author
$VALID_LEVEL"""
    }
}
