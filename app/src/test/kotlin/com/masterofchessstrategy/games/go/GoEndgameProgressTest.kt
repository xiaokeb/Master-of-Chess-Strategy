package com.masterofchessstrategy.games.go

import org.junit.Assert.*
import org.junit.Test

class GoEndgameProgressTest {
    private val ids = GoEndgameLibrary.puzzles.map { it.id }

    @Test fun everyContiguousPrefixRoundTripsWithOnlyNextPuzzleUnlocked() {
        for (count in 0..ids.size) {
            val last = ids[minOf(count, ids.lastIndex)]
            val progress = GoEndgameProgress(ids.take(count).toSet(), last)
            val decoded = checkNotNull(GoEndgameProgressCodec.decode(GoEndgameProgressCodec.encode(progress)))
            assertEquals(progress.completedIds, decoded.completedIds); assertEquals(last, decoded.lastPuzzleId)
            ids.forEachIndexed { index, id -> assertEquals(index <= count, decoded.isUnlocked(id)) }
        }
    }

    @Test fun repeatedCompletionDoesNotAddAnotherPuzzleAndReturnPositionIsValidated() {
        val completed = GoEndgameProgress().complete(ids.first()).complete(ids.first())
        assertEquals(setOf(ids.first()), completed.completedIds)
        assertEquals(ids[1], completed.select(ids[1]).lastPuzzleId)
        assertInvalid { completed.select(ids[2]) }; assertInvalid { completed.complete(ids[2]) }
    }

    @Test fun unknownIdsGapsAndLockedReturnPositionsCannotGrantProgress() {
        assertInvalid { GoEndgameProgress(setOf("unknown")) }
        assertInvalid { GoEndgameProgress(setOf(ids[1])) }
        assertInvalid { GoEndgameProgress(setOf(ids[0], ids[2])) }
        assertInvalid { GoEndgameProgress(lastPuzzleId = ids[1]) }
    }

    @Test fun externalMutationCannotChangeCompletionSet() {
        val external = mutableSetOf(ids.first())
        val progress = GoEndgameProgress(external)
        external.clear(); assertEquals(setOf(ids.first()), progress.completedIds)
        try { (progress.completedIds as MutableSet).clear(); fail("Mutable progress exposed") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun corruptionTruncationAndTrailingBytesAreRejected() {
        val bytes = GoEndgameProgressCodec.encode(GoEndgameProgress(ids.take(2).toSet(), ids[2]))
        for (i in bytes.indices) {
            val damaged = bytes.copyOf(); damaged[i] = (damaged[i].toInt() xor 1).toByte()
            assertNull("Corrupt byte $i accepted", GoEndgameProgressCodec.decode(damaged))
            assertNull("Truncation $i accepted", GoEndgameProgressCodec.decode(bytes.copyOf(i)))
        }
        assertNull(GoEndgameProgressCodec.decode(bytes + 0))
        assertNull(GoEndgameProgressCodec.decode(ByteArray(GoEndgameProgressCodec.MAX_BYTES + 1)))
    }

    @Test fun validCrcCannotAuthorizeUnknownVersionDuplicateIdsOrProgressGaps() {
        for (body in listOf("GEP1\n2\n${ids[0]}\n\n", "GEP1\n1\n${ids[0]}\n${ids[0]},${ids[0]}\n",
            "GEP1\n1\n${ids[0]}\n${ids[1]}\n", "GEP1\n1\n${ids[0]}\n${ids[1]},${ids[0]}\n")) {
            val crc = java.util.zip.CRC32().apply { update(body.toByteArray()) }.value.toString(16).padStart(8, '0')
            assertNull(GoEndgameProgressCodec.decode("$body$crc\n".toByteArray()))
        }
    }

    @Test fun v1MigrationPreservesCompletionAndReturnPositionWithoutInventingDailyState() {
        val body = "GEP1\n1\n${ids[2]}\n${ids.take(2).joinToString(",")}\n"
        val old = withCrc(body)
        val restored = checkNotNull(GoEndgameProgressCodec.decode(old))
        assertEquals(ids.take(2).toSet(), restored.completedIds); assertEquals(ids[2], restored.lastPuzzleId)
        assertEquals(GoEndgameChallengeMode.MAINLINE, restored.lastMode)
        assertNull(restored.dailyDate); assertNull(restored.dailyPuzzleId)
        val saved = GoEndgameProgressCodec.encode(restored)
        assertTrue(saved.toString(Charsets.UTF_8).startsWith("GEP1\n2\n1\n"))
        assertEquals(restored.completedIds, GoEndgameProgressCodec.decode(saved)!!.completedIds)
    }

    @Test fun v2RoundTripsEveryModeAndFixedDailyAcrossOtherModeSelection() {
        val date = java.time.LocalDate.of(2026, 10, 14)
        val original = GoEndgameProgress(ids.take(3).toSet())
        val reserved = original.selectDaily(date, GoEndgameChallenges.daily(original, date).id)
        for (mode in GoEndgameChallengeMode.entries) {
            val active = if (mode == GoEndgameChallengeMode.DAILY) reserved else reserved.select(ids[2], mode)
            val restored = checkNotNull(GoEndgameProgressCodec.decode(GoEndgameProgressCodec.encode(active)))
            assertEquals(mode, restored.lastMode); assertEquals(active.lastPuzzleId, restored.lastPuzzleId)
            assertEquals(active.dailyPuzzleId, restored.dailyPuzzleId); assertEquals(date, restored.dailyDate)
        }
    }

    @Test fun validCrcCannotAuthorizeWrongV2ModeDateOrDailyMetadata() {
        for (suffix in listOf("BAD\n-\n-\n", "DAILY\n-\n-\n", "MAINLINE\n2026-02-30\n${ids[0]}\n",
            "MAINLINE\n2026-10-14\n-\n", "MAINLINE\n2026-10-14\n${ids[1]}\n")) {
            assertNull(GoEndgameProgressCodec.decode(withCrc("GEP1\n2\n1\n${ids[0]}\n\n$suffix")))
        }
    }

    private fun withCrc(body: String): ByteArray {
        val crc = java.util.zip.CRC32().apply { update(body.toByteArray()) }.value.toString(16).padStart(8, '0')
        return "$body$crc\n".toByteArray()
    }

    private fun assertInvalid(block: () -> Unit) {
        try { block(); fail("Invalid progress accepted") } catch (_: IllegalArgumentException) { }
    }
}
