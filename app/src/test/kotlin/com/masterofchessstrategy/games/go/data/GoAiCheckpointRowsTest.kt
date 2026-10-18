package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.games.go.*
import org.junit.Assert.*
import org.junit.Test

/** Pure row/GAI ownership checks only; these bytes are not Native legality evidence. */
class GoAiCheckpointRowsTest {
    private fun match() = GoAiSavedMatch("row/一", GoAiMatchConfig(13, 5, null, GoAiDifficulty.HARD),
        byteArrayOf(13, 5, 0, 1), undoUsed = 1, hintUsed = 1,
        rememberedHuman = GoPlayer.WHITE, automaticUsed = true, openingBytes = byteArrayOf(13, 5),
        autoContinueEnabled = true, autoContinueMaxGames = 7, autoContinueGameNumber = 3)
    private fun entry(lineage: GoAiCheckpointLineage = GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION)) =
        GoAiCheckpointEntry(GoAiMatchFileCodec.encode(match()), lineage)

    @Test fun activeRoundTripOwnsAllBytesAndKeepsEveryGaiPolicyField() {
        val original = entry()
        val row = GoAiCheckpointRows.active(original, 12)
        val restored = GoAiCheckpointRows.entry(row)
        row.checkpoint.fill(0)
        restored.copyBytes().fill(0)
        assertArrayEquals(original.copyBytes(), restored.copyBytes())
        val saved = GoAiMatchFileCodec.decode(restored.copyBytes())!!
        assertEquals(GoPlayer.WHITE, saved.rememberedHuman)
        assertTrue(saved.automaticUsed && saved.autoContinueEnabled)
        assertEquals(7, saved.autoContinueMaxGames)
        assertEquals(3, saved.autoContinueGameNumber)
        assertEquals(1, saved.undoUsed); assertEquals(1, saved.hintUsed)
        assertArrayEquals(match().copyOpeningBytes(), saved.copyOpeningBytes())
    }

    @Test fun originStorageCodesAreExplicitAndAllThreeRoundTripWithoutUpgrading() {
        val parent = "a".repeat(64)
        val cases = listOf(GoAiCheckpointLineage(GoAiCheckpointOrigin.LEGACY_UNVERIFIED) to 1,
            GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION) to 2,
            GoAiCheckpointLineage(GoAiCheckpointOrigin.TERMINAL_CONTINUATION, parent) to 3)
        for ((lineage, code) in cases) {
            val row = GoAiCheckpointRows.active(entry(lineage), 0)
            assertEquals(code, row.originCode)
            assertEquals(lineage, GoAiCheckpointRows.entry(row).lineage)
        }
    }

    @Test fun archiveRoundTripOwnsBytesAndKeepsFirstCompletionTimeAndParent() {
        val source = GoAiCheckpointArchive("b".repeat(64), entry(GoAiCheckpointLineage(
            GoAiCheckpointOrigin.TERMINAL_CONTINUATION, "a".repeat(64))), 27)
        val row = GoAiCheckpointRows.archive(source)
        val restored = GoAiCheckpointRows.archive(row)
        row.checkpoint.fill(0)
        assertEquals(source.recordId, restored.recordId)
        assertEquals(27L, restored.completedAtEpochMillis)
        assertEquals(source.checkpoint.lineage, restored.checkpoint.lineage)
        assertArrayEquals(source.checkpoint.copyBytes(), restored.checkpoint.copyBytes())
    }

    @Test fun invalidRowOwnershipVersionTimeOrProvenanceFailsClosed() {
        val row = GoAiCheckpointRows.active(entry(), 12)
        for (invalid in listOf(row.copy(gameTypeCode = 0), row.copy(envelopeVersion = 5),
            row.copy(updatedAtEpochMillis = -1), row.copy(originCode = 0), row.copy(originCode = 99),
            row.copy(originCode = 3), row.copy(replacedTerminalRecordId = "a".repeat(64)),
            row.copy(originCode = 3, replacedTerminalRecordId = "NOT-AN-ID"))) {
            rejects { GoAiCheckpointRows.entry(invalid) }
        }
        rejects { GoAiCheckpointRows.active(entry(), -1) }
    }

    @Test fun corruptEnvelopeAndLegacyVersionCannotMasqueradeAsCanonicalRoomImage() {
        val row = GoAiCheckpointRows.active(entry(), 12)
        rejects { GoAiCheckpointRows.entry(row.copy(checkpoint = byteArrayOf(1, 2, 3))) }
        val corrupt = row.checkpoint.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        rejects { GoAiCheckpointRows.entry(row.copy(checkpoint = corrupt)) }
        val old = row.checkpoint.copyOf().apply { this[4] = 5 }
        rejects { GoAiCheckpointRows.entry(row.copy(checkpoint = old)) }
    }

    @Test fun archiveRejectsSelfParentInvalidKeyAndNegativeCompletionTime() {
        val row = GoAiCheckpointRows.archive(GoAiCheckpointArchive("b".repeat(64), entry(), 27))
        rejects { GoAiCheckpointRows.archive(row.copy(recordId = "bad")) }
        rejects { GoAiCheckpointRows.archive(row.copy(completedAtEpochMillis = -1)) }
        rejects { GoAiCheckpointRows.archive(row.copy(originCode = 3, replacedTerminalRecordId = row.recordId)) }
    }

    @Test fun completeEnvelopeBudgetIsNotTruncatedToTheNativeImageBudget() {
        val saved = GoAiSavedMatch("a".repeat(128), GoAiMatchConfig(19), ByteArray(1_000_000) { 1 },
            openingBytes = ByteArray(1_000_000) { 2 })
        val image = GoAiMatchFileCodec.encode(saved)
        assertEquals(GoAiMatchFileCodec.MAX_BYTES, image.size)
        val restored = GoAiCheckpointRows.entry(GoAiCheckpointRows.active(GoAiCheckpointEntry(image,
            GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION)), 0))
        assertArrayEquals(image, restored.copyBytes())
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid AI Room row accepted") } catch (_: IllegalArgumentException) { }
    }
}
