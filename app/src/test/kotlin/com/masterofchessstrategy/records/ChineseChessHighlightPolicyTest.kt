package com.masterofchessstrategy.records

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.HighlightCondition
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import java.util.zip.CRC32
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseChessHighlightPolicyTest {
    @Test
    fun disabledConditionsAndNonAutoPlayGamesDoNotAutoFavorite() {
        val master = record(difficulty = Difficulty.MASTER)

        assertFalse(ChineseChessHighlightPolicy.matches(master, 0))
        assertFalse(
            ChineseChessHighlightPolicy.matches(
                master.copy(mode = StoredGameMode.HUMAN_VS_AI),
                HighlightCondition.MASTER.bit,
            ),
        )
    }

    @Test
    fun selectedMasterAndLongGameConditionsAreIndependent() {
        val master = record(difficulty = Difficulty.MASTER)
        val long = record(moveCount = 160)

        assertTrue(ChineseChessHighlightPolicy.matches(master, HighlightCondition.MASTER.bit))
        assertFalse(ChineseChessHighlightPolicy.matches(master, HighlightCondition.LONG_GAME.bit))
        assertTrue(ChineseChessHighlightPolicy.matches(long, HighlightCondition.LONG_GAME.bit))
        assertFalse(ChineseChessHighlightPolicy.matches(long, HighlightCondition.MASTER.bit))
    }

    @Test
    fun materialDeficitFollowedByWinQualifiesAsComeback() {
        for (version in 2..4) {
            val state = mocxState(
                finalPieces = listOf(
                4 to 0x81, // Black general.
                85 to 1, // Red general.
                49 to 7, // Red soldier blocks the generals.
                0 to 0x85, // Black chariot.
                1 to 0x86, // Black cannon.
                ),
                capturedPieces = listOf(5), // A red chariot was lost.
                version = version,
            )
            val game = record(state = state, result = GameResult.FIRST_PLAYER_WIN)

            assertTrue(ChineseChessHighlightPolicy.matches(game, HighlightCondition.COMEBACK.bit))
            assertFalse(
                ChineseChessHighlightPolicy.matches(
                    game.copy(result = GameResult.DRAW),
                    HighlightCondition.COMEBACK.bit,
                ),
            )
        }
    }

    @Test
    fun noCapturedDeficitOrInvalidHistoryDoesNotClaimComeback() {
        val withoutLoss = record(state = mocxState(emptyList(), emptyList()))
        val corrupt = record(state = mocxState(emptyList(), listOf(5)).also {
            it[20] = (it[20].toInt() xor 1).toByte()
        })

        assertFalse(ChineseChessHighlightPolicy.matches(withoutLoss, HighlightCondition.COMEBACK.bit))
        assertFalse(ChineseChessHighlightPolicy.matches(corrupt, HighlightCondition.COMEBACK.bit))
    }

    @Test
    fun unknownOrMalformedV4RuleMarkerDoesNotClaimComeback() {
        val original = mocxState(listOf(0 to 0x85, 1 to 0x86), listOf(5), version = 4)
        assertTrue(ChineseChessHighlightPolicy.matches(record(state = original), HighlightCondition.COMEBACK.bit))
        val current = original.copyOf().apply { this[size - 5] = 1 }
        reseal(current)
        assertTrue(ChineseChessHighlightPolicy.matches(record(state = current), HighlightCondition.COMEBACK.bit))
        listOf(2, 255).forEach { revision ->
            val unknown = original.copyOf().apply { this[size - 5] = revision.toByte() }
            reseal(unknown)
            assertFalse(ChineseChessHighlightPolicy.matches(record(state = unknown), HighlightCondition.COMEBACK.bit))
        }
        val missing = original.copyOf(original.size - 1)
        reseal(missing)
        assertFalse(ChineseChessHighlightPolicy.matches(record(state = missing), HighlightCondition.COMEBACK.bit))
        val extra = original.copyOf(original.size + 1)
        reseal(extra)
        assertFalse(ChineseChessHighlightPolicy.matches(record(state = extra), HighlightCondition.COMEBACK.bit))
    }

    private fun record(
        difficulty: Difficulty = Difficulty.EASY,
        moveCount: Int = 1,
        state: ByteArray = byteArrayOf(1),
        result: GameResult = GameResult.FIRST_PLAYER_WIN,
    ) = GameRecord(
        recordId = "highlight-test",
        gameType = GameType.CHINESE_CHESS,
        mode = StoredGameMode.AI_AUTO_PLAY,
        difficulty = difficulty,
        result = result,
        engineState = state,
        moveCount = moveCount,
        completedAtEpochMillis = 1L,
    )

    private fun mocxState(
        finalPieces: List<Pair<Int, Int>>,
        capturedPieces: List<Int>,
        version: Int = 2,
    ): ByteArray {
        val data = ByteArray(12 + 90 + 11 * capturedPieces.size +
            (if (version >= 3) 2 else 0) + (if (version == 4) 1 else 0) + 4)
        "MOCX".encodeToByteArray().copyInto(data)
        data[4] = version.toByte()
        data[10] = capturedPieces.size.toByte()
        finalPieces.forEach { (square, code) -> data[12 + square] = code.toByte() }
        capturedPieces.forEachIndexed { index, code ->
            data[12 + 90 + 11 * index + 5] = code.toByte()
        }
        reseal(data)
        return data
    }

    private fun reseal(data: ByteArray) {
        val crc = CRC32().apply { update(data, 0, data.size - 4) }.value
        repeat(4) { byte -> data[data.size - 4 + byte] = (crc ushr (8 * byte)).toByte() }
    }
}
