package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.engine.Difficulty
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LanzhouMahjongSessionRecordTest {
    @Test
    fun roundTripPreservesEveryDifficultySeatAndLimit() {
        for (difficulty in Difficulty.entries) {
            for (viewer in 0..3) {
                for (dealer in 0..3) {
                    val record = record(difficulty, viewer, dealer,
                        seed = Long.MAX_VALUE,
                        base = LanzhouMahjongSessionRecord.MAX_BASE_SCORE,
                        nextChoiceSeed = Long.MAX_VALUE)
                    val restored = decode(encode(record))!!
                    assertEquals(difficulty, restored.difficulty)
                    assertEquals(viewer, restored.viewerSeat)
                    assertEquals(dealer, restored.dealerSeat)
                    assertEquals(Long.MAX_VALUE, restored.seed)
                    assertEquals(LanzhouMahjongSessionRecord.MAX_BASE_SCORE, restored.baseScore)
                    assertEquals(Long.MAX_VALUE, restored.nextChoiceSeed)
                    assertEquals(LanzhouMahjongSessionRecord.RULE_VERSION, restored.ruleVersion)
                    assertArrayEquals(record.nativeState, restored.nativeState)
                }
            }
        }
        val zeroSeeds = decode(encode(record(seed = 0, nextChoiceSeed = 0)))!!
        assertEquals(0L, zeroSeeds.seed)
        assertEquals(0L, zeroSeeds.nextChoiceSeed)
        assertEquals(1L, zeroSeeds.baseScore)
    }

    @Test
    fun nativeLengthBoundariesAreAcceptedWithoutClaimingLegalReplay() {
        val initial = record()
        assertEquals(130, initial.nativeState.size)
        assertNotNull(decode(encode(initial)))
        // This is a structural envelope fixture; native restore must still verify its actions.
        val longest = record(nativeState = nativeState(actionCount = 1000))
        assertEquals(3130, longest.nativeState.size)
        assertEquals(3186, encode(longest).size)
        assertArrayEquals(longest.nativeState, decode(encode(longest))!!.nativeState)
        val maximum = LanzhouMahjongSessionRecord(Difficulty.EASY, 0, 7, 0, 1, 17,
            nativeState(actionCount = 1000, kind = 1),
            decisions = List(1000) { LanzhouMahjongDecisionPoint(it, it.toLong()) })
        assertEquals(LanzhouMahjongSessionRecordCodec.MAX_BYTES, encode(maximum).size)
        assertEquals(maximum.decisions, decode(encode(maximum))!!.decisions)
    }

    @Test
    fun constructorGetterAndCodecOwnTheirByteArrays() {
        val original = nativeState()
        val expected = original.copyOf()
        val saved = record(nativeState = original)
        original.fill(0)
        assertArrayEquals(expected, saved.nativeState)
        saved.nativeState.fill(0)
        assertArrayEquals(expected, saved.nativeState)
        val encoded = encode(saved)
        val restored = decode(encoded)!!
        encoded.fill(0)
        assertArrayEquals(expected, restored.nativeState)
        restored.nativeState.fill(0)
        assertArrayEquals(expected, restored.nativeState)
        val freshEncoding = encode(saved)
        freshEncoding.fill(0)
        assertArrayEquals(expected, decode(encode(saved))!!.nativeState)
    }

    @Test
    fun constructorRejectsInvalidMetadata() {
        for (viewer in listOf(-1, 4, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { record(viewer = viewer) }
        }
        for (dealer in listOf(-1, 4, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { record(dealer = dealer) }
        }
        assertThrows(IllegalArgumentException::class.java) { record(seed = -1) }
        assertThrows(IllegalArgumentException::class.java) { record(nextChoiceSeed = -1) }
        assertThrows(IllegalArgumentException::class.java) { record(base = 0) }
        assertThrows(IllegalArgumentException::class.java) { record(base = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            record(base = LanzhouMahjongSessionRecord.MAX_BASE_SCORE + 1)
        }
    }

    @Test
    fun constructorRejectsNativeEnvelopeAndMetadataMismatch() {
        val badStates = listOf(
            ByteArray(129), ByteArray(3131),
            nativeState().apply { this[0] = 0 },
            nativeState().apply { this[4] = 3; refreshNativeChecksum(this) },
            nativeState().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() },
            nativeState().apply { buffer(this).putInt(122, 1001); refreshNativeChecksum(this) },
            nativeState().apply { buffer(this).putInt(122, -1); refreshNativeChecksum(this) },
            nativeState().apply { buffer(this).putInt(122, 1); refreshNativeChecksum(this) },
            nativeState(dealer = 1), nativeState(base = 2),
        )
        for (bytes in badStates) {
            assertThrows(IllegalArgumentException::class.java) { record(nativeState = bytes) }
        }
    }

    @Test
    fun decoderRejectsMagicFormatRuleAndReservedVersions() {
        val original = encode(record())
        for (offset in listOf(0, 4, 5, 9, 10, 11)) {
            val changed = original.copyOf()
            changed[offset] = (changed[offset].toInt() xor 0x7f).toByte()
            refreshCrc(changed)
            assertNull("Accepted altered header byte $offset", decode(changed))
        }
    }

    @Test
    fun decoderRejectsInvalidMetadataEvenWithValidCrc() {
        val original = encode(record())
        for (offset in listOf(6, 7, 8)) {
            val changed = original.copyOf()
            changed[offset] = 4
            refreshCrc(changed)
            assertNull("Accepted invalid code at $offset", decode(changed))
        }
        for ((offset, value) in listOf(
            12 to -1L, 28 to -1L, 20 to 0L, 20 to -1L,
            20 to LanzhouMahjongSessionRecord.MAX_BASE_SCORE + 1,
        )) {
            val changed = original.copyOf()
            buffer(changed).putLong(offset, value)
            refreshCrc(changed)
            assertNull("Accepted invalid long at $offset", decode(changed))
        }
        val mismatchedDealer = original.copyOf().apply { this[8] = 1; refreshCrc(this) }
        assertNull(decode(mismatchedDealer))
        val mismatchedBase = original.copyOf().apply { buffer(this).putLong(20, 2); refreshCrc(this) }
        assertNull(decode(mismatchedBase))
    }

    @Test
    fun decoderRejectsTruncationTrailingDataAndInvalidLengths() {
        val original = encode(record())
        for (length in 0 until original.size) {
            assertNull("Accepted truncated length $length", decode(original.copyOf(length)))
        }
        assertNull(decode(ByteArray(LanzhouMahjongSessionRecordCodec.MAX_BYTES + 1)))
        val trailing = original.copyOf(original.size + 1)
        refreshCrc(trailing)
        assertNull(decode(trailing))
        for (length in listOf(-1, 0, 129, 131, 3131, Int.MAX_VALUE)) {
            val changed = original.copyOf()
            buffer(changed).putInt(36, length)
            refreshCrc(changed)
            assertNull("Accepted wrong native length $length", decode(changed))
        }
    }

    @Test
    fun crcCoversMetadataNativeStateAndFooter() {
        val original = encode(record())
        for (offset in listOf(12, 28, 54, original.lastIndex)) {
            val changed = original.copyOf()
            changed[offset] = (changed[offset].toInt() xor 1).toByte()
            assertNull("Accepted corrupt byte $offset", decode(changed))
        }
    }

    @Test
    fun decoderRejectsInvalidNativeVersionLengthAndChecksumWithValidOuterCrc() {
        val original = encode(record())
        for (offset in listOf(40, 44, 40 + 122, 40 + 129)) {
            val changed = original.copyOf()
            changed[offset] = (changed[offset].toInt() xor 1).toByte()
            refreshCrc(changed)
            assertNull("Accepted invalid native byte $offset", decode(changed))
        }
    }

    @Test
    fun bothNativeRuleIdentitiesRoundTripWithoutRewritingBytes() {
        for (version in 1..2) {
            val native = nativeState().apply {
                this[4] = version.toByte()
                refreshNativeChecksum(this)
            }
            val saved = record(nativeState = native)
            assertArrayEquals(native, decode(encode(saved))!!.nativeState)
            // Old outer v1 records must retain the same native rule as outer v2.
            val current = encode(saved)
            val legacy = current.copyOfRange(0, 40 + native.size + 4)
            legacy[4] = 1
            refreshCrc(legacy)
            assertArrayEquals(native, decode(legacy)!!.nativeState)
        }
    }

    private fun record(
        difficulty: Difficulty = Difficulty.MEDIUM,
        viewer: Int = 0,
        dealer: Int = 0,
        seed: Long = 7,
        base: Long = 1,
        nextChoiceSeed: Long = 17,
        nativeState: ByteArray = nativeState(dealer, base),
    ) = LanzhouMahjongSessionRecord(
        difficulty, viewer, seed, dealer, base, nextChoiceSeed, nativeState,
    )

    private fun nativeState(dealer: Int = 0, base: Long = 1, actionCount: Int = 0, kind: Int = 0): ByteArray {
        val output = buffer(ByteArray(130 + actionCount * 3))
        output.put(byteArrayOf(76, 90, 77, 74, 1, dealer.toByte()))
        output.putLong(base)
        repeat(108) { output.put((it / 4).toByte()) }
        output.putInt(actionCount)
        repeat(actionCount) { output.put(byteArrayOf(kind.toByte(), 0, if (kind == 0) 255.toByte() else 0)) }
        refreshNativeChecksum(output.array())
        return output.array()
    }

    private fun refreshNativeChecksum(bytes: ByteArray) {
        var checksum = 0x811c9dc5.toInt()
        for (index in 0 until bytes.size - 4) {
            checksum = (checksum xor (bytes[index].toInt() and 0xff)) * 16777619
        }
        buffer(bytes).putInt(bytes.size - 4, checksum)
    }

    private fun refreshCrc(bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt()
        buffer(bytes).putInt(bytes.size - 4, crc)
    }

    private fun buffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun encode(record: LanzhouMahjongSessionRecord) = LanzhouMahjongSessionRecordCodec.encode(record)
    private fun decode(bytes: ByteArray) = LanzhouMahjongSessionRecordCodec.decode(bytes)
}
