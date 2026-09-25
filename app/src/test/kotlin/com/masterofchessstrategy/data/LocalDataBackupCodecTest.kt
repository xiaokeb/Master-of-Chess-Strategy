package com.masterofchessstrategy.data

import com.masterofchessstrategy.challenge.TimedChallengeConfig
import com.masterofchessstrategy.challenge.StreakChallengeState
import com.masterofchessstrategy.challenge.StreakChallengeStateCodec
import com.masterofchessstrategy.challenge.AssessmentChallengeState
import com.masterofchessstrategy.challenge.AssessmentChallengeStateCodec
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.custom.CustomPositionStateCodec
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
        assertTrue(text.startsWith("MOCS-BACKUP|2\nCREATED|900\n"))
        assertTrue(text.substringAfterLast("SHA256|").trim().matches(Regex("[0-9a-f]{64}")))
        assertFalse('\r' in text)
    }

    @Test
    fun selectedAppearanceRoundTripsWithSettings() {
        val source = completeSnapshot().copy(
            settings = requireNotNull(completeSnapshot().settings).copy(
                selectedAppearanceCode = 4,
            ),
        )

        val restored = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(source))

        assertEquals(4, restored.settings?.selectedAppearanceCode)
    }

    @Test
    fun highlightConditionsRoundTripAndLegacyBackupDefaultsToOff() {
        val settings = AppSettings.DEFAULT.copy(
            highlightConditionsMask = HighlightCondition.COMEBACK.bit or
                HighlightCondition.LONG_GAME.bit,
            updatedAtEpochMillis = 2L,
        )
        val source = LocalDataSnapshot(createdAtEpochMillis = 1L, settings = settings)

        val restored = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(source))
        assertEquals(settings.highlightConditionsMask, restored.settings?.highlightConditionsMask)

        val legacyBody = "MOCS-BACKUP|1\nCREATED|1\nSETTINGS|0|0|10|1|-|0|2\n"
        val checksum = java.security.MessageDigest.getInstance("SHA-256")
            .digest(legacyBody.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val legacy = (legacyBody + "SHA256|$checksum\n").toByteArray(Charsets.UTF_8)
        val imported = LocalDataBackupCodec.decode(legacy)
        assertEquals(0, imported.settings?.highlightConditionsMask)
        assertEquals(2L, imported.settings?.updatedAtEpochMillis)
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

    @Test
    fun customPositionSessionRoundTripsItsCanonicalInitialState() {
        val initial = byteArrayOf(1, 2, 3)
        val source = LocalDataSnapshot(
            createdAtEpochMillis = 1L,
            activeSessions = listOf(
                GameSessionSnapshot(
                    gameType = GameType.CHINESE_CHESS,
                    mode = StoredGameMode.CUSTOM_POSITION,
                    difficulty = Difficulty.EASY,
                    engineState = byteArrayOf(4, 5, 6),
                    updatedAtEpochMillis = 2L,
                    sessionId = "custom-1",
                    sessionVariantId = CustomPositionStateCodec.sessionVariant(initial),
                ),
            ),
        )

        val restored = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(source))

        assertEquals(
            CustomPositionStateCodec.sessionVariant(initial),
            restored.activeSessions.single().sessionVariantId,
        )
    }

    @Test
    fun timedChallengeRoundTripsAndRejectsClockAboveItsPerMoveLimit() {
        val session = GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.TIMED_CHALLENGE,
            difficulty = Difficulty.EASY,
            engineState = byteArrayOf(7),
            updatedAtEpochMillis = 2L,
            sessionId = "timed-1",
            timeControlMinutes = TimedChallengeConfig.BACKING_CLOCK_MINUTES,
            redRemainingMillis = 10_000L,
            blackRemainingMillis = 4_000L,
            turnStartedAtEpochMillis = 1L,
            sessionVariantId = TimedChallengeConfig.sessionVariant(10),
        )
        val source = LocalDataSnapshot(
            createdAtEpochMillis = 1L,
            activeSessions = listOf(session),
        )

        val restored = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(source))

        assertEquals(
            TimedChallengeConfig.sessionVariant(10),
            restored.activeSessions.single().sessionVariantId,
        )
        assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.encode(
                source.copy(
                    activeSessions = listOf(
                        session.copy(redRemainingMillis = 10_001L),
                    ),
                ),
            )
        }
    }

    @Test
    fun streakChallengeSessionAndSelectionRoundTripTogether() {
        val state = StreakChallengeState(
            currentStreak = 4,
            bestStreak = 6,
            winsAtDifficulty = 1,
        )
        val source = LocalDataSnapshot(
            createdAtEpochMillis = 1L,
            activeSessions = listOf(
                GameSessionSnapshot(
                    gameType = GameType.CHINESE_CHESS,
                    mode = StoredGameMode.STREAK_CHALLENGE,
                    difficulty = Difficulty.MEDIUM,
                    engineState = byteArrayOf(7, 8),
                    updatedAtEpochMillis = 2L,
                    sessionId = "streak-1",
                    sessionVariantId = StreakChallengeStateCodec.encode(state),
                ),
            ),
            lastSelections = listOf(
                LastGameSelection(
                    gameType = GameType.CHINESE_CHESS,
                    mode = StoredGameMode.STREAK_CHALLENGE,
                    difficulty = Difficulty.MEDIUM,
                    updatedAtEpochMillis = 3L,
                ),
            ),
        )

        val restored = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(source))

        assertEquals(StoredGameMode.STREAK_CHALLENGE, restored.activeSessions.single().mode)
        assertEquals(Difficulty.MEDIUM, restored.lastSelections.single().difficulty)
        assertEquals(
            state,
            StreakChallengeStateCodec.decode(
                restored.activeSessions.single().sessionVariantId,
            ),
        )
    }

    @Test
    fun blindChallengeSessionAndSelectionRoundTripTogether() {
        val source = LocalDataSnapshot(
            createdAtEpochMillis = 1L,
            activeSessions = listOf(
                GameSessionSnapshot(
                    gameType = GameType.CHINESE_CHESS,
                    mode = StoredGameMode.BLIND_CHALLENGE,
                    difficulty = Difficulty.MEDIUM,
                    engineState = byteArrayOf(7, 8),
                    updatedAtEpochMillis = 2L,
                    sessionId = "blind-1",
                ),
            ),
            lastSelections = listOf(
                LastGameSelection(
                    gameType = GameType.CHINESE_CHESS,
                    mode = StoredGameMode.BLIND_CHALLENGE,
                    difficulty = Difficulty.MEDIUM,
                    updatedAtEpochMillis = 3L,
                ),
            ),
        )

        val restored = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(source))

        assertEquals(StoredGameMode.BLIND_CHALLENGE, restored.activeSessions.single().mode)
        assertEquals(Difficulty.MEDIUM, restored.lastSelections.single().difficulty)
        assertEquals("", restored.activeSessions.single().sessionVariantId)
    }

    @Test
    fun assessmentSessionAndSelectionRoundTripTogether() {
        val state = AssessmentChallengeState(
            completedGames = 2,
            rating = 1_500,
            wins = 1,
            draws = 1,
        )
        val source = LocalDataSnapshot(
            createdAtEpochMillis = 1L,
            activeSessions = listOf(
                GameSessionSnapshot(
                    gameType = GameType.CHINESE_CHESS,
                    mode = StoredGameMode.ASSESSMENT_CHALLENGE,
                    difficulty = Difficulty.HARD,
                    engineState = byteArrayOf(7, 8),
                    updatedAtEpochMillis = 2L,
                    sessionId = "assessment-1",
                    sessionVariantId = AssessmentChallengeStateCodec.encode(state),
                ),
            ),
            lastSelections = listOf(
                LastGameSelection(
                    gameType = GameType.CHINESE_CHESS,
                    mode = StoredGameMode.ASSESSMENT_CHALLENGE,
                    difficulty = Difficulty.HARD,
                    updatedAtEpochMillis = 3L,
                ),
            ),
        )

        val restored = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(source))

        assertEquals(StoredGameMode.ASSESSMENT_CHALLENGE, restored.activeSessions.single().mode)
        assertEquals(
            state,
            AssessmentChallengeStateCodec.decode(
                restored.activeSessions.single().sessionVariantId,
            ),
        )
    }

    @Test
    fun openingAutoPlayRoundTripsItsCanonicalInitialPosition() {
        val initial = byteArrayOf(1, 2, 3)
        val source = LocalDataSnapshot(
            createdAtEpochMillis = 1L,
            activeSessions = listOf(
                GameSessionSnapshot(
                    gameType = GameType.CHINESE_CHESS,
                    mode = StoredGameMode.OPENING_AUTO_PLAY,
                    difficulty = Difficulty.MEDIUM,
                    engineState = byteArrayOf(7),
                    updatedAtEpochMillis = 2L,
                    sessionId = "opening-auto-1",
                    sessionVariantId = CustomPositionStateCodec.sessionVariant(initial),
                ),
            ),
        )

        val restored = LocalDataBackupCodec.decode(LocalDataBackupCodec.encode(source))

        assertEquals(StoredGameMode.OPENING_AUTO_PLAY, restored.activeSessions.single().mode)
        assertEquals(
            initial.toList(),
            CustomPositionStateCodec.decodeSessionVariant(
                restored.activeSessions.single().sessionVariantId,
            ).toList(),
        )
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
            selectedAppearanceCode = 3,
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
