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

        assertEquals(5, pack.version)
        assertEquals("xq-easy-001@v5", pack.sessionVariantId("xq-easy-001"))
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
    fun headerOrderAndPieceBeforeMoveOrderAreRequired() {
        val swappedHeader = VALID_PACK.replace(
            "LICENSE|GPL-3.0-or-later\nAUTHOR|Test Author",
            "AUTHOR|Test Author\nLICENSE|GPL-3.0-or-later",
        )
        val repeatedHeader = VALID_PACK + "MOCS-XQ-ENDGAMES|5\n"
        val latePiece = VALID_PACK.replace(
            "PIECE|3|1|RED|CHARIOT\nMOVE|3|1|4|1",
            "MOVE|3|1|4|1\nPIECE|3|1|RED|CHARIOT",
        )

        listOf(swappedHeader, repeatedHeader, latePiece).forEach {
            assertTrue(runCatching { ChineseChessEndgamePackParser.parse(it) }.isFailure)
        }
    }

    @Test
    fun bundledPackParsesEveryDifficultyAndUsesProjectLicense() {
        val content = File("src/main/assets/endgames/chinese_chess/endgames-v5.txt")
            .readText(Charsets.UTF_8)

        val pack = ChineseChessEndgamePackParser.parse(content)

        assertEquals("GPL-3.0-or-later", pack.license)
        assertEquals(Difficulty.entries.toSet(), pack.levels.map { it.difficulty }.toSet())
        assertEquals(14, pack.levels.size)
        assertEquals(9, pack.levels.count { it.theme == "唯一一步杀" })
        assertEquals(5, pack.levels.count { it.theme == "多解胜局" })
        assertEquals(5, pack.levels.count { it.track == ChineseChessEndgameTrack.MAIN })
        assertEquals(9, pack.levels.count { it.track == ChineseChessEndgameTrack.BONUS })
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

    @Test
    fun unreachableUncrossedSoldierAdvisorAndElephantSquaresAreRejected() {
        listOf(
            "PIECE|5|6|RED|SOLDIER",
            "PIECE|5|3|BLACK|SOLDIER",
            "PIECE|4|9|RED|ADVISOR",
            "PIECE|4|2|BLACK|ADVISOR",
            "PIECE|5|5|RED|ELEPHANT",
            "PIECE|5|4|BLACK|ELEPHANT",
        ).forEach { piece ->
            var malformed = VALID_PACK.replace("PIECE|3|1|RED|CHARIOT", piece)
            if (piece == "PIECE|4|9|RED|ADVISOR") {
                malformed = malformed.replace(
                    "PIECE|4|9|RED|GENERAL",
                    "PIECE|3|9|RED|GENERAL",
                )
            }
            assertTrue(piece, runCatching { ChineseChessEndgamePackParser.parse(malformed) }.isFailure)
        }
    }

    @Test
    fun reachableSoldierAdvisorAndElephantSquaresRemainAccepted() {
        listOf(
            "PIECE|2|6|RED|SOLDIER",
            "PIECE|2|4|BLACK|SOLDIER",
            "PIECE|3|9|RED|ADVISOR",
            "PIECE|3|0|BLACK|ADVISOR",
            "PIECE|2|5|RED|ELEPHANT",
            "PIECE|2|4|BLACK|ELEPHANT",
        ).forEach { piece ->
            val variation = VALID_PACK.replace("PIECE|3|1|RED|CHARIOT", piece)
            assertTrue(piece, runCatching { ChineseChessEndgamePackParser.parse(variation) }.isSuccess)
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
        const val VALID_PACK = """MOCS-XQ-ENDGAMES|5
LICENSE|GPL-3.0-or-later
AUTHOR|Test Author
$VALID_LEVEL"""
    }
}
