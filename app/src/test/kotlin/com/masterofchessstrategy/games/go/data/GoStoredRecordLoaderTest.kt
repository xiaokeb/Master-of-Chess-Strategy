package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.GoSessionIdentity
import com.masterofchessstrategy.games.go.GoSessionOrigin
import org.junit.Assert.*
import org.junit.Test

/** Provenance and byte ownership only; these fixtures are not evidence of Native/Room admission. */
class GoStoredRecordLoaderTest {
    private val identity = GoSessionIdentity("stored-record-test", GoSessionOrigin.SGF_IMPORT, 2)
    private val bytes = byteArrayOf(77, 79, 67, 71, 3, 9, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private fun record() = GameRecord(identity.recordId, GameType.GO, StoredGameMode.LOCAL_TWO_PLAYER,
        null, GameResult.DRAW, bytes.copyOf(), 0, completedAtEpochMillis = 17)
    private fun source() = GoRecordSourceEntity(identity.recordId, GameType.GO.code,
        identity.sessionId, identity.origin.code, identity.archiveRevision)

    @Test fun historicalRecordWithoutSourceRemainsUnknownAndRetainsItsId() {
        val original = record().copy(recordId = "historical-no-source")
        val loaded = storedGoRecordSnapshot(original, null)
        assertNull(loaded.source)
        assertEquals(GoSessionOrigin.LEGACY_UNKNOWN, loaded.origin)
        assertEquals(original.recordId, loaded.record.recordId)
    }

    @Test fun validLocalProvenanceRetainsItsOriginalIdentityAndOrigin() {
        val loaded = storedGoRecordSnapshot(record(), source())
        assertEquals(identity, loaded.source)
        assertEquals(GoSessionOrigin.SGF_IMPORT, loaded.origin)
        assertEquals(2, loaded.source!!.archiveRevision)
    }

    @Test fun callerAndReaderCannotMutateTheStoredSnapshot() {
        val original = record()
        val loaded = storedGoRecordSnapshot(original, source())
        original.engineState.fill(0)
        loaded.record.engineState.fill(1)
        assertArrayEquals(bytes, loaded.record.engineState)
        assertEquals(17L, loaded.record.completedAtEpochMillis)
    }

    @Test fun foreignRecordOrSourceOwnerAndMismatchedRecordIdsAreRejected() {
        val foreign = GameRecord("xiangqi", GameType.CHINESE_CHESS, StoredGameMode.LOCAL_TWO_PLAYER,
            null, GameResult.DRAW, byteArrayOf(1), 0, completedAtEpochMillis = 1)
        assertThrows(IllegalArgumentException::class.java) { storedGoRecordSnapshot(foreign, null) }
        for (bad in listOf(source().copy(gameTypeCode = GameType.CHINESE_CHESS.code),
            source().copy(recordId = "other"), source().copy(sessionId = "other-session"))) {
            assertThrows(IllegalArgumentException::class.java) { storedGoRecordSnapshot(record(), bad) }
        }
    }

    @Test fun corruptOrUnsupportedIdentityFieldsAreRejected() {
        for (bad in listOf(source().copy(originCode = 99), source().copy(archiveRevision = -1),
            source().copy(sessionId = "invalid identity"))) {
            assertThrows(IllegalArgumentException::class.java) { storedGoRecordSnapshot(record(), bad) }
        }
    }

    @Test fun sourcedRecordsCannotMasqueradeAsRankedAiEndgameOrAnotherPlayer() {
        val original = record()
        val invalid = listOf(original.copy(mode = StoredGameMode.HUMAN_VS_AI),
            original.copy(difficulty = Difficulty.HARD), original.copy(mode = StoredGameMode.HUMAN_VS_AI, playerIndex = 1),
            original.copy(mode = StoredGameMode.ENDGAME, isEndgame = true))
        for (bad in invalid) {
            assertThrows(IllegalArgumentException::class.java) { storedGoRecordSnapshot(bad, source()) }
        }
    }
}
