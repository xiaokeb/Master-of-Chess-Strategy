package com.masterofchessstrategy.engine

import com.masterofchessstrategy.engine.internal.NativeBindings
import org.junit.Assert.*
import org.junit.Test

/** Real JNI only; no App files, Room, reset, or opponent-hand debugging query. */
class NativeCompetitionMahjongInstrumentedTest {
    @Test fun fixedViewerPrivacyMoveUndoAndPrivateRecordRoundTrip() {
        for (viewer in 0..3) NativeCompetitionMahjongSession(925, roundWind = 2, viewerSeat = viewer).use { session ->
            val view = session.snapshot()
            assertEquals(viewer, view.viewerSeat)
            assertEquals(2, view.roundWind)
            for (seat in view.seats)
                assertEquals(if (seat.seat == viewer) seat.concealedCount else 0, seat.concealedTiles.size)
            val initial = session.serialize()
            NativeCompetitionMahjongSession(43, viewerSeat = viewer).use { restored ->
                assertEquals(RestoreResult.Restored, restored.restore(initial))
                assertEquals(view, restored.snapshot())
                assertArrayEquals(initial, restored.serialize())
            }
            if (viewer == 0) {
                assertEquals(0, session.advanceBots(0,1,256))
                val action = session.legalActions().first { it.kind == CompetitionMahjongRoundActionKind.DISCARD }
                assertTrue(session.apply(action).accepted)
                assertEquals(CompetitionMahjongRoundPhase.CLAIM, session.snapshot().phase)
                assertTrue(session.undo())
                assertArrayEquals(initial, session.serialize())
            }
        }
    }

    @Test fun corruptOrOtherViewerRecordNeverChangesTheCurrentSession() {
        NativeCompetitionMahjongSession(925).use { first ->
            NativeCompetitionMahjongSession(43, viewerSeat = 1).use { second ->
                val untouched = second.serialize()
                assertTrue(second.restore(first.serialize()) is RestoreResult.Rejected)
                assertArrayEquals(untouched, second.serialize())
                val corrupt = untouched.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
                assertTrue(second.restore(corrupt) is RestoreResult.Rejected)
                assertArrayEquals(untouched, second.serialize())
                assertTrue(second.restore(byteArrayOf()) is RestoreResult.Rejected)
                assertTrue(second.restore(ByteArray(1_048_577)) is RestoreResult.Rejected)
                assertArrayEquals(untouched, second.serialize())
            }
        }
    }

    @Test fun botsStopForTheLocalSeatAndClosedOrWrongHandlesAreRejected() {
        NativeCompetitionMahjongSession(925, viewerSeat = 3).use { session ->
            assertTrue(session.legalActions().isEmpty())
            assertEquals(1, session.advanceBots(0,9821,1))
            for (step in 0 until 200) {
                if (session.legalActions().isNotEmpty()) break
                session.advanceBots(0,9821L + step,1)
                session.snapshot()
            }
            assertTrue(session.legalActions().isNotEmpty())
            assertEquals(0, session.advanceBots(0,9821,256))
        }
        val session = NativeCompetitionMahjongSession(925)
        session.close(); session.close()
        assertIllegalState { session.snapshot() }
        val chess = NativeBindings.createChineseChessEngine()
        try {
            assertIllegalState { NativeBindings.competitionMahjongSnapshot(chess) }
            NativeBindings.releaseCompetitionMahjong(chess)
            assertEquals(0, NativeBindings.chineseChessCurrentPlayer(chess))
        } finally { NativeBindings.releaseChineseChessEngine(chess) }
    }

    @Test fun winningFanAndFalseDeclarationCrossTheRealBridge() {
        NativeCompetitionMahjongSession(925).use { session ->
            assertEquals(RestoreResult.Restored, session.restore(fixtureRecord(
                listOf(0,0,0,1,1,1,2,2,2,3,3,3,4,4), 0)))
            val win = session.apply(CompetitionMahjongAction(CompetitionMahjongRoundActionKind.WIN,4))
            assertTrue(win.accepted)
            assertFalse(win.falseWin)
            val view = session.snapshot()
            assertEquals(CompetitionMahjongRoundPhase.WON, view.phase)
            assertEquals(0, view.winnerSeat)
            assertTrue(view.winningFan.entries.any { it.name == "清一色" })
            val payment = 8 + view.winningFan.totalFan
            assertEquals(listOf(3 * payment,-payment,-payment,-payment), view.netScores)
            NativeCompetitionMahjongSession(43).use { restored ->
                assertEquals(RestoreResult.Restored, restored.restore(session.serialize()))
                assertEquals(view, restored.snapshot())
            }
            assertEquals(RestoreResult.Restored, session.restore(fixtureRecord(
                listOf(0,2,4,6,8,9,11,13,15,17,18,20,22,24),0)))
            val falseWin = session.apply(CompetitionMahjongAction(CompetitionMahjongRoundActionKind.WIN,24))
            assertTrue(falseWin.accepted && falseWin.falseWin)
            assertEquals(CompetitionMahjongRoundError.NONE, falseWin.error)
            assertEquals(listOf(-60,20,20,20), session.snapshot().netScores)
            assertTrue(session.snapshot().seats[0].winDisabled)
        }
    }

    @Test fun opponentConcealedKongNeverCrossesTheBridge() {
        for (viewer in 0..3) NativeCompetitionMahjongSession(925, viewerSeat = viewer).use { session ->
            assertEquals(RestoreResult.Restored, session.restore(fixtureRecord(
                listOf(31,31,31,31),viewer,listOf(listOf(6,0,31,0)))))
            val meld = session.snapshot().seats[0].melds.single()
            assertEquals(CompetitionMahjongMeldKind.KONG, meld.kind)
            assertEquals(if (viewer == 0) 31 else null, meld.tile)
            for (seat in session.snapshot().seats)
                if (seat.seat != viewer) assertTrue(seat.concealedTiles.isEmpty())
        }
    }

    /** Test-owned synthetic MOCM/MCMS fixtures, never an App/user save file. */
    private fun fixtureRecord(firstHand: List<Int>, viewer: Int,
                              events: List<List<Int>> = emptyList()): ByteArray {
        val remaining = IntArray(42) { if (it < 34) 4 else 1 }
        val hands = Array(4) { mutableListOf<Int>() }
        hands[0].addAll(firstHand)
        for (tile in firstHand) check(--remaining[tile] >= 0)
        for (seat in 0..3) {
            val target = if (seat == 0) 14 else 13
            for (tile in 0..33) while (remaining[tile] > 0 && hands[seat].size < target) {
                hands[seat].add(tile); --remaining[tile]
            }
            check(hands[seat].size == target)
        }
        val positions = IntArray(4)
        val wall = mutableListOf<Int>()
        repeat(3) { for (seat in 0..3) repeat(4) { wall.add(hands[seat][positions[seat]++]) } }
        repeat(2) { wall.add(hands[0][positions[0]++]) }
        for (seat in 1..3) wall.add(hands[seat][positions[seat]++])
        for (tile in 0..41) repeat(remaining[tile]) { wall.add(tile) }
        check(wall.size == 144)
        val payload = mutableListOf(77,79,67,77,1,0,0)
        payload.addAll(wall)
        put32(payload, events.size.toLong())
        for (event in events) { check(event.size == 4); payload.addAll(event) }
        var checksum = 2166136261L
        for (byte in payload) checksum = ((checksum xor byte.toLong()) * 16777619L) and 0xffffffffL
        put32(payload,checksum)
        val envelope = mutableListOf(77,67,77,83,1,viewer,0,0)
        put32(envelope,payload.size.toLong())
        envelope.addAll(payload)
        val crc = java.util.zip.CRC32()
        crc.update(envelope.map { it.toByte() }.toByteArray())
        put32(envelope,crc.value)
        return envelope.map { it.toByte() }.toByteArray()
    }

    private fun put32(target: MutableList<Int>, value: Long) {
        for (shift in 0..3) target.add(((value ushr (8 * shift)) and 255).toInt())
    }

    private fun assertIllegalState(action: () -> Unit) {
        try { action(); fail("Invalid handle accepted") } catch (_: IllegalStateException) { }
    }
}
