package com.masterofchessstrategy.engine

import com.masterofchessstrategy.engine.internal.CompetitionMahjongAutoBindings
import com.masterofchessstrategy.engine.internal.NativeBindings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

/** Real JNI only, with synthetic walls. No App database, settings or user record is touched. */
class NativeCompetitionMahjongAutoInstrumentedTest {
    private fun config(viewer: Int = 0) = CompetitionMahjongAutoConfig(
        925, viewerSeat = viewer, botLevels = listOf(0, 1, 2, 3), decisionSeed = 7,
    )

    @Test fun fixedViewerActsAutomaticallyWithoutExposingOtherHands() {
        for (viewer in 0..3) NativeCompetitionMahjongAutoSession(config(viewer)).use { session ->
            val before = session.serialize()
            assertEquals(1, session.advance(1).acceptedActions)
            assertFalse(before.contentEquals(session.serialize()))
            val view = session.snapshot()
            assertEquals(viewer, view.viewerSeat)
            assertEquals(2, view.publicFrameVersion)
            for (seat in view.seats) {
                if (seat.seat == viewer) assertEquals(seat.concealedCount, seat.concealedTiles.size)
                else assertTrue(seat.concealedTiles.isEmpty())
            }
            assertTrue(view.winningTiles.isEmpty())
        }
    }

    @Test fun batchesAndObserverSelectionDoNotChangeTheActualTrajectory() {
        NativeCompetitionMahjongAutoSession(config()).use { single ->
            NativeCompetitionMahjongAutoSession(config()).use { batch ->
                NativeCompetitionMahjongAutoSession(config(3)).use { otherObserver ->
                    repeat(2) {
                        val result = batch.advance(32)
                        repeat(result.acceptedActions) { assertEquals(1, single.advance(1).acceptedActions) }
                        assertEquals(result, otherObserver.advance(32))
                        assertArrayEquals(batch.serialize(), single.serialize())
                        assertEquals(batch.snapshot(), single.snapshot())
                        val first = batch.serialize()
                        val other = otherObserver.serialize()
                        // MCMA's viewer/header CRC differs; the actual private Round must not.
                        assertArrayEquals(first.copyOfRange(24, first.size - 4), other.copyOfRange(24, other.size - 4))
                    }
                }
            }
        }
    }

    @Test fun restoresTheExactRecordAndRejectsWrongIdentityOrDamageAtomically() {
        NativeCompetitionMahjongAutoSession(config()).use { source ->
            source.advance(20)
            val record = source.serialize()
            assertEquals("MCMA", record.take(4).map { it.toInt().toChar() }.joinToString(""))
            assertEquals(1, record[4].toInt())
            NativeCompetitionMahjongAutoSession(config()).use { restored ->
                assertEquals(RestoreResult.Restored, restored.restore(record))
                assertArrayEquals(record, restored.serialize())
                assertEquals(source.snapshot(), restored.snapshot())
                assertEquals(source.advance(5), restored.advance(5))
                assertArrayEquals(source.serialize(), restored.serialize())
                val stable = restored.serialize()
                val unknown = record.copyOf().also { it[4] = 2; repairCrc(it) }
                val reserved = record.copyOf().also { it[10] = 1; repairCrc(it) }
                val damaged = record.copyOf().also { it[24] = 0 }
                for (bad in listOf(byteArrayOf(), record.copyOf(27), unknown, reserved, damaged, ByteArray(1_048_577))) {
                    assertEquals(RestoreResult.Rejected(EngineError.CORRUPTED_DATA), restored.restore(bad))
                    assertArrayEquals(stable, restored.serialize())
                }
                NativeCompetitionMahjongSession(925).use { human ->
                    assertEquals(RestoreResult.Rejected(EngineError.CORRUPTED_DATA), restored.restore(human.serialize()))
                    assertEquals(RestoreResult.Rejected(EngineError.CORRUPTED_DATA), human.restore(record))
                }
            }
            val incompatible = listOf(config(1), CompetitionMahjongAutoConfig(925, botLevels = listOf(0, 0, 0, 0), decisionSeed = 7),
                CompetitionMahjongAutoConfig(925, botLevels = listOf(0, 1, 2, 3), decisionSeed = 8))
            for (setup in incompatible) NativeCompetitionMahjongAutoSession(setup).use { target ->
                val stable = target.serialize()
                assertEquals(RestoreResult.Rejected(EngineError.CORRUPTED_DATA), target.restore(record))
                assertArrayEquals(stable, target.serialize())
            }
        }
    }

    @Test fun completeHandStopsWithoutContinuationAndUndoReplaysTheFinalEvent() {
        NativeCompetitionMahjongAutoSession(config()).use { session ->
            var accepted = 0
            var ended = false
            repeat(512) {
                if (!ended) {
                    val advanced = session.advance(32)
                    accepted += advanced.acceptedActions
                    ended = advanced.terminal
                }
            }
            assertTrue("Automatic JNI hand did not finish", ended)
            assertTrue(accepted > 0)
            val terminal = session.snapshot()
            assertTrue(terminal.phase in listOf(CompetitionMahjongRoundPhase.WON, CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW))
            assertEquals(0, terminal.netScores.sum())
            val saved = session.serialize()
            assertEquals(CompetitionMahjongAutoAdvance(0, true), session.advance(32))
            assertArrayEquals(saved, session.serialize())
            assertTrue(session.undo())
            assertEquals(CompetitionMahjongAutoAdvance(1, true), session.advance(1))
            assertEquals(terminal, session.snapshot())
            assertArrayEquals(saved, session.serialize())
        }
    }

    @Test fun jniValidatesRawConfigurationAndBudgetsBeforeMutation() {
        for (levels in listOf(intArrayOf(), intArrayOf(0, 0, 0), intArrayOf(0, 0, 0, 4), intArrayOf(-1, 0, 0, 0))) {
            assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoBindings.create(925, 0, 0, 0, levels, 0) }
        }
        for (setup in listOf(longArrayOf(-1, 0, 0, 0, 0), longArrayOf(0, 4, 0, 0, 0),
            longArrayOf(0, 0, -1, 0, 0), longArrayOf(0, 0, 0, 4, 0), longArrayOf(0, 0, 0, 0, -1))) {
            assertThrows(IllegalArgumentException::class.java) {
                CompetitionMahjongAutoBindings.create(setup[0], setup[1].toInt(), setup[2].toInt(), setup[3].toInt(), intArrayOf(0, 0, 0, 0), setup[4])
            }
        }
        val handle = CompetitionMahjongAutoBindings.create(Long.MAX_VALUE, 3, 3, 3, intArrayOf(3, 2, 1, 0), Long.MAX_VALUE)
        try {
            val stable = CompetitionMahjongAutoBindings.serialize(handle)
            for (budget in listOf(-1, 0, 33, Int.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoBindings.advance(handle, budget) }
                assertArrayEquals(stable, CompetitionMahjongAutoBindings.serialize(handle))
            }
        } finally { CompetitionMahjongAutoBindings.release(handle) }
    }

    @Test fun wrongTypeLookupsAndReleasesDoNotDestroyEitherSession() {
        val human = NativeBindings.createCompetitionMahjong(925, 0, 0, 0)
        val automatic = CompetitionMahjongAutoBindings.create(925, 0, 0, 0, intArrayOf(0, 0, 0, 0), 0)
        try {
            val humanRecord = NativeBindings.serializeCompetitionMahjong(human)
            val autoRecord = CompetitionMahjongAutoBindings.serialize(automatic)
            assertThrows(IllegalStateException::class.java) { NativeBindings.competitionMahjongSnapshot(automatic) }
            assertThrows(IllegalStateException::class.java) { NativeBindings.advanceCompetitionMahjongBots(automatic, 0, 0, 1) }
            assertThrows(IllegalStateException::class.java) { CompetitionMahjongAutoBindings.snapshot(human) }
            assertThrows(IllegalStateException::class.java) { CompetitionMahjongAutoBindings.advance(human, 1) }
            NativeBindings.releaseCompetitionMahjong(automatic)
            CompetitionMahjongAutoBindings.release(human)
            assertArrayEquals(humanRecord, NativeBindings.serializeCompetitionMahjong(human))
            assertArrayEquals(autoRecord, CompetitionMahjongAutoBindings.serialize(automatic))
        } finally {
            NativeBindings.releaseCompetitionMahjong(human)
            CompetitionMahjongAutoBindings.release(automatic)
        }
    }

    @Test fun closeIsIdempotentAndConcurrentCallsAreOrderedOrRejected() {
        val session = NativeCompetitionMahjongAutoSession(config())
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val reader = Thread {
            try {
                check(start.await(10, TimeUnit.SECONDS))
                repeat(32) {
                    try { session.snapshot(); session.advance(1) }
                    catch (closed: IllegalStateException) { assertEquals("Automatic Mahjong session is closed", closed.message) }
                }
            } catch (failure: Throwable) { failures.add(failure) }
            finally { done.countDown() }
        }
        val closer = Thread {
            try { check(start.await(10, TimeUnit.SECONDS)); session.close(); session.close() }
            catch (failure: Throwable) { failures.add(failure) }
            finally { done.countDown() }
        }
        reader.start(); closer.start(); start.countDown()
        try {
            assertTrue(done.await(20, TimeUnit.SECONDS))
            assertTrue(failures.joinToString(), failures.isEmpty())
            assertThrows(IllegalStateException::class.java) { session.snapshot() }
            assertThrows(IllegalStateException::class.java) { session.advance(1) }
            assertThrows(IllegalStateException::class.java) { session.undo() }
            assertThrows(IllegalStateException::class.java) { session.serialize() }
            assertThrows(IllegalStateException::class.java) { session.restore(byteArrayOf()) }
        } finally { session.close() }
    }

    @Test fun automaticAndHumanSessionsShareTheSameTerminalPublicEncoder() {
        val hand = listOf(31,31,31,31,0,0,0,1,1,1,2,2,2,3)
        val events = listOf(listOf(6,0,31,0), listOf(8,0,3,0))
        for (viewer in 0..3) {
            val humanRecord = CompetitionMahjongNativeTestRecords.record(hand, viewer, events, listOf(3))
            NativeCompetitionMahjongSession(925, viewerSeat = viewer).use { human ->
                NativeCompetitionMahjongAutoSession(config(viewer)).use { automatic ->
                    assertEquals(RestoreResult.Restored, human.restore(humanRecord))
                    val automaticRecord = wrapRound(humanRecord.copyOfRange(12, humanRecord.size - 4), config(viewer))
                    assertEquals(RestoreResult.Restored, automatic.restore(automaticRecord))
                    val view = automatic.snapshot()
                    assertEquals(human.snapshot(), view)
                    assertEquals(3, view.winningTile)
                    assertEquals(11, view.winningTiles.size)
                    assertEquals(31, view.seats[0].melds.single().tile)
                    for (seat in view.seats) if (seat.seat != viewer) assertTrue(seat.concealedTiles.isEmpty())
                }
            }
        }
    }

    private fun repairCrc(bytes: ByteArray) {
        val crc = CRC32().also { it.update(bytes, 0, bytes.size - 4) }.value
        repeat(4) { bytes[bytes.size - 4 + it] = (crc ushr (8 * it)).toByte() }
    }

    private fun wrapRound(payload: ByteArray, config: CompetitionMahjongAutoConfig): ByteArray {
        val bytes = ByteArray(28 + payload.size)
        "MCMA".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[4] = 1; bytes[5] = config.viewerSeat.toByte()
        config.botLevels.forEachIndexed { index, level -> bytes[6 + index] = level.toByte() }
        repeat(8) { bytes[12 + it] = (config.decisionSeed ushr (8 * it)).toByte() }
        repeat(4) { bytes[20 + it] = (payload.size ushr (8 * it)).toByte() }
        payload.copyInto(bytes, 24)
        repairCrc(bytes)
        return bytes
    }
}
