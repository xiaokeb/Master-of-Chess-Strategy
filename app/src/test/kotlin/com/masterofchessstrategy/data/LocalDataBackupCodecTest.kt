package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataBackupCodecTest {
    @Test
    fun fullSnapshotRoundTripsWithCanonicalChecksum() {
        val source = completeSnapshot()

        val encoded = LocalDataBackupCodec.encode(source)
        val restored = LocalDataBackupCodec.decode(encoded)

        assertEquals(source.createdAtEpochMillis, restored.createdAtEpochMillis)
        assertEquals(source.settings, restored.settings)
        assertEquals(source.lastSelections, restored.lastSelections)
        assertEquals(source.tutorialProgress, restored.tutorialProgress)
        assertEquals(source.matchOutcomes, restored.matchOutcomes)
        assertEquals(source.endgameProgress, restored.endgameProgress)
        assertEquals(source.activeSessions.single().mode, restored.activeSessions.single().mode)
        assertArrayEquals(
            source.activeSessions.single().engineState,
            restored.activeSessions.single().engineState,
        )
        assertEquals(source.gameRecords.single().recordId, restored.gameRecords.single().recordId)
        assertArrayEquals(
            source.gameRecords.single().engineState,
            restored.gameRecords.single().engineState,
        )
        val text = encoded.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("MOCS-BACKUP|1\nCREATED|900\n"))
        assertTrue(text.substringAfterLast("SHA256|").trim().matches(Regex("[0-9a-f]{64}")))
        assertFalse('\r' in text)
    }

    @Test
    fun anyPayloadChangeFailsBeforeDecode() {
        val encoded = LocalDataBackupCodec.encode(completeSnapshot())
        val changed = encoded.copyOf()
        val marker = changed.indexOf('A'.code.toByte())
        changed[marker] = 'B'.code.toByte()

        assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.decode(changed)
        }
    }

    @Test
    fun duplicatePrimaryKeysAndInvalidVariantModeAreRejected() {
        val source = completeSnapshot()
        assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.encode(
                source.copy(gameRecords = source.gameRecords + source.gameRecords.single()),
            )
        }
        val invalidSession = source.activeSessions.single().copy(
            mode = StoredGameMode.LOCAL_TWO_PLAYER,
            difficulty = null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.encode(
                source.copy(activeSessions = listOf(invalidSession)),
            )
        }
    }

    private fun completeSnapshot(): LocalDataSnapshot = LocalDataSnapshot(
        createdAtEpochMillis = 900L,
        activeSessions = listOf(
            GameSessionSnapshot(
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.ENDGAME,
                difficulty = Difficulty.EASY,
                engineState = byteArrayOf(1, 2, 3),
                updatedAtEpochMillis = 100L,
                sessionId = "对局-1",
                acceptedMoveCount = 1,
                hintUseCount = 1,
                resultOverride = null,
                pendingDrawOfferSide = ChineseChessSide.RED,
                sessionVariantId = "xq-easy-001",
            ),
        ),
        lastSelections = listOf(
            LastGameSelection(
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.ENDGAME,
                difficulty = null,
                updatedAtEpochMillis = 110L,
            ),
        ),
        settings = AppSettings(
            defaultDifficulty = Difficulty.HARD,
            autoContinueEnabled = true,
            autoContinueGameLimit = 12,
            soundEnabled = false,
            gameDurationMinutes = null,
            updatedAtEpochMillis = 120L,
        ),
        tutorialProgress = listOf(
            TutorialProgress(
                gameType = GameType.CHINESE_CHESS,
                contentVersion = 1,
                completedStepCount = 4,
                isCompleted = true,
                updatedAtEpochMillis = 130L,
            ),
        ),
        matchOutcomes = listOf(
            MatchOutcome(
                matchId = "match-1",
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.HUMAN_VS_AI,
                difficulty = Difficulty.MEDIUM,
                playerIndex = 0,
                result = GameResult.FIRST_PLAYER_WIN,
                settledAtEpochMillis = 140L,
            ),
        ),
        gameRecords = listOf(
            GameRecord(
                recordId = "record-1",
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.ENDGAME,
                difficulty = Difficulty.EASY,
                result = GameResult.FIRST_PLAYER_WIN,
                engineState = byteArrayOf(4, 5, 6),
                moveCount = 1,
                isFavorite = true,
                isEndgame = true,
                completedAtEpochMillis = 150L,
            ),
        ),
        endgameProgress = listOf(
            CompletedEndgameLevel(
                levelId = "xq-easy-001",
                difficulty = Difficulty.EASY,
                bestPlayerMoves = 1,
                starsAwarded = 1,
                scoreAwarded = 10,
                completedAtEpochMillis = 160L,
            ),
        ),
    )
}
