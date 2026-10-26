package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind as Kind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class LanzhouMahjongDecisionHistoryTest {
    @Test
    fun v2PreservesCountersAndPrivateDecisionHistory() {
        val points = mutableListOf(LanzhouMahjongDecisionPoint(0, 11), LanzhouMahjongDecisionPoint(4, 19))
        val usage = LanzhouMahjongAssistanceUsage(Difficulty.MEDIUM, 1, 2)
        val record = record(native(6), usage, points)
        val bytes = LanzhouMahjongSessionRecordCodec.encode(record)
        points.clear()
        assertEquals(2, record.decisions.size)
        (record.decisions as MutableList).clear()
        assertEquals(2, record.decisions.size)
        val restored = LanzhouMahjongSessionRecordCodec.decode(bytes)!!
        bytes.fill(0)
        assertEquals(usage, restored.assistance)
        assertEquals(record.decisions, restored.decisions)
        assertArrayEquals(record.nativeState, restored.nativeState)
    }

    @Test
    fun legacyV1MigratesWithoutInventingDecisionPoints() {
        val bytes = LanzhouMahjongSessionRecordCodec.encode(record(native(4)))
        val legacy = bytes.copyOf(40 + 142 + 4)
        legacy[4] = 1
        crc(legacy)
        val restored = LanzhouMahjongSessionRecordCodec.decode(legacy)!!
        assertEquals(0, restored.assistance.undoUsed)
        assertEquals(0, restored.assistance.hintUsed)
        assertTrue(restored.decisions.isEmpty())
        assertEquals(4, LanzhouMahjongNativeHistory.actionCount(restored.nativeState))
        assertEquals(2, LanzhouMahjongSessionRecordCodec.encode(restored)[4].toInt())
    }

    @Test
    fun invalidCountersAndDecisionMetadataAreRejectedEvenWithValidCrc() {
        val record = record(native(6), points = listOf(LanzhouMahjongDecisionPoint(0, 11),
            LanzhouMahjongDecisionPoint(4, 19)))
        val original = LanzhouMahjongSessionRecordCodec.encode(record)
        val start = 40 + record.nativeState.size
        for ((offset, value) in listOf(start to -1, start + 4 to 4,
            start + 8 to -1, start + 8 to 1001,
            start + 12 to -1, start + 12 to 6, start + 24 to 0)) {
            val changed = original.copyOf()
            buffer(changed).putInt(offset, value)
            crc(changed)
            assertNull("Accepted field $offset=$value", LanzhouMahjongSessionRecordCodec.decode(changed))
        }
        val negativeSeed = original.copyOf()
        buffer(negativeSeed).putLong(start + 16, -1)
        crc(negativeSeed)
        assertNull(LanzhouMahjongSessionRecordCodec.decode(negativeSeed))
    }

    @Test
    fun checkpointsMustIdentifyLocalNonDrawActionsAndMatchDifficulty() {
        val point = listOf(LanzhouMahjongDecisionPoint(0, 11))
        assertThrows(IllegalArgumentException::class.java) {
            record(native(1, player = 1), points = point)
        }
        assertThrows(IllegalArgumentException::class.java) {
            record(native(1, kind = Kind.DRAW.code), points = point)
        }
        assertThrows(IllegalArgumentException::class.java) {
            record(native(1), LanzhouMahjongAssistanceUsage(Difficulty.HARD))
        }
        assertThrows(IllegalArgumentException::class.java) {
            LanzhouMahjongSessionRecord(Difficulty.MASTER, 0, 1, 0, 1, 20,
                native(1), decisions = point)
        }
        assertThrows(IllegalArgumentException::class.java) { LanzhouMahjongDecisionPoint(1000, 0) }
    }

    @Test
    fun undoPrefixDropsEveryLaterActionAndRejectsCorruptedOrOutOfRangeInput() {
        val bytes = native(8)
        val before = bytes.copyOf()
        val prefix = LanzhouMahjongNativeHistory.prefix(bytes, 3)!!
        assertEquals(3, LanzhouMahjongNativeHistory.actionCount(prefix))
        assertArrayEquals(bytes.copyOfRange(0, 122), prefix.copyOfRange(0, 122))
        assertArrayEquals(bytes.copyOfRange(126, 135), prefix.copyOfRange(126, 135))
        assertArrayEquals(before, bytes)
        for (badCount in listOf(-1, 8, 1000)) {
            assertNull(LanzhouMahjongNativeHistory.prefix(bytes, badCount))
        }
        assertNull(LanzhouMahjongNativeHistory.prefix(bytes.copyOf().apply { this[14] = 99 }, 3))
        assertNull(LanzhouMahjongNativeHistory.actionCount(ByteArray(129)))
    }

    @Test
    fun forcedActionsDoNotAddCheckpointsAndUndoKeepsUsedHints() {
        var seed = 11L
        val manager = LanzhouMahjongAssistanceManager(Difficulty.MEDIUM,
            choiceSeed = { seed }, restoreChoiceSeed = { seed = it })
        val discard = LanzhouMahjongAction(Kind.DISCARD, 0)
        val other = LanzhouMahjongAction(Kind.DISCARD, 1)
        val draw = LanzhouMahjongAction(Kind.DRAW)
        val pass = LanzhouMahjongAction(Kind.PASS)
        assertNull(manager.beforeAction(native(0), listOf(draw), draw))
        assertNull(manager.beforeAction(native(0), listOf(pass), pass))
        assertNull(manager.beforeAction(native(0), listOf(discard), discard))
        manager.accepted(manager.beforeAction(native(0), listOf(discard, other), discard))
        seed = 29
        assertTrue(manager.useHint())
        val plan = manager.undoPlan(native(8))!!
        assertEquals(0, LanzhouMahjongNativeHistory.actionCount(plan.nativeState))
        plan.nativeState.fill(0)
        assertEquals(0, LanzhouMahjongNativeHistory.actionCount(plan.nativeState))
        manager.acceptUndo(plan)
        assertEquals(11L, seed)
        assertEquals(1, manager.usage.undoUsed)
        assertEquals(1, manager.usage.hintUsed)
        assertFalse(manager.canUndo)
        assertNull(manager.undoPlan(native(0)))
        val decliningWin = LanzhouMahjongAssistanceManager(Difficulty.HARD,
            choiceSeed = { 0 }, restoreChoiceSeed = {})
        assertNotNull(decliningWin.beforeAction(native(0),
            listOf(pass, LanzhouMahjongAction(Kind.WIN)), pass))
    }

    private fun record(
        native: ByteArray,
        usage: LanzhouMahjongAssistanceUsage = LanzhouMahjongAssistanceUsage(Difficulty.MEDIUM),
        points: List<LanzhouMahjongDecisionPoint> = emptyList(),
    ) = LanzhouMahjongSessionRecord(Difficulty.MEDIUM, 0, 1, 0, 1, 20, native, usage, points)

    private fun native(count: Int, player: Int = 0, kind: Int = Kind.DISCARD.code): ByteArray {
        val bytes = ByteArray(130 + count * 3)
        val output = buffer(bytes)
        output.put(byteArrayOf(76, 90, 77, 74, 1, 0)).putLong(1)
        repeat(108) { output.put((it / 4).toByte()) }
        output.putInt(count)
        repeat(count) { output.put(byteArrayOf(kind.toByte(), player.toByte(), 0)) }
        var hash = 0x811c9dc5.toInt()
        for (index in 0 until bytes.size - 4) hash = (hash xor (bytes[index].toInt() and 255)) * 16777619
        output.putInt(hash)
        return bytes
    }

    private fun crc(bytes: ByteArray) {
        buffer(bytes).putInt(bytes.size - 4, CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt())
    }
    private fun buffer(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
