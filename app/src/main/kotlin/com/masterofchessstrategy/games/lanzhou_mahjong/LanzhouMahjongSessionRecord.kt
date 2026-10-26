package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.engine.Difficulty
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Private native bytes belong in persistence, never in table rendering or log messages. */
internal class LanzhouMahjongSessionRecord(
    val difficulty: Difficulty,
    val viewerSeat: Int,
    val seed: Long,
    val dealerSeat: Int,
    val baseScore: Long,
    val nextChoiceSeed: Long,
    nativeState: ByteArray,
    val assistance: LanzhouMahjongAssistanceUsage = LanzhouMahjongAssistanceUsage(difficulty),
    decisions: List<LanzhouMahjongDecisionPoint> = emptyList(),
) {
    companion object {
        /** Outer 108-tile container identity, not the native penalty revision in byte 4. */
        const val RULE_VERSION = 1
        const val MAX_BASE_SCORE = Long.MAX_VALUE / 96
    }

    private val ownedNativeState: ByteArray
    private val ownedDecisions = decisions.toList()
    val nativeState: ByteArray get() = ownedNativeState.copyOf()
    val decisions: List<LanzhouMahjongDecisionPoint> get() = ownedDecisions.toList()
    val ruleVersion: Int get() = RULE_VERSION

    init {
        require(viewerSeat in 0..3 && dealerSeat in 0..3)
        require(seed >= 0 && nextChoiceSeed >= 0)
        require(baseScore in 1..MAX_BASE_SCORE)
        require(nativeState.size in MIN_NATIVE_BYTES..MAX_NATIVE_BYTES)
        ownedNativeState = nativeState.copyOf()
        require(validNativeEnvelope(ownedNativeState, dealerSeat, baseScore)) {
            "Invalid Lanzhou Mahjong native record"
        }
        require(assistance.difficulty == difficulty)
        require(ownedDecisions.size <= 1000)
        val actionCount = (ownedNativeState.size - MIN_NATIVE_BYTES) / 3
        var previous = -1
        for (decision in ownedDecisions) {
            require(decision.actionCount > previous && decision.actionCount < actionCount)
            val offset = 126 + decision.actionCount * 3
            require((ownedNativeState[offset].toInt() and 255) in 1..8 &&
                (ownedNativeState[offset + 1].toInt() and 255) == viewerSeat)
            previous = decision.actionCount
        }
        require(difficulty != Difficulty.MASTER || ownedDecisions.isEmpty())
    }

    override fun toString(): String =
        "LanzhouMahjongSessionRecord(difficulty=$difficulty, viewerSeat=$viewerSeat, dealerSeat=$dealerSeat)"
}

/** Missing records return null; storage failures and incompatible records must be reported. */
internal interface LanzhouMahjongSessionStore {
    suspend fun load(): LanzhouMahjongSessionRecord?
    /** Replace atomically; a failed save must preserve the previous complete record. */
    suspend fun save(record: LanzhouMahjongSessionRecord)
}

/** LZSR v2 adds usage and decision points after the native bytes; v1 remains readable. */
internal object LanzhouMahjongSessionRecordCodec {
    private const val MAGIC = 0x52535a4c
    const val VERSION = 2
    private const val HEADER_BYTES = 40
    private const val FOOTER_BYTES = 4
    private const val ASSISTANCE_BYTES = 12
    private const val DECISION_BYTES = 12
    const val MAX_BYTES = HEADER_BYTES + MAX_NATIVE_BYTES + ASSISTANCE_BYTES + 1000 * DECISION_BYTES + FOOTER_BYTES

    fun encode(record: LanzhouMahjongSessionRecord): ByteArray {
        val nativeState = record.nativeState
        val decisions = record.decisions
        val output = ByteBuffer.allocate(HEADER_BYTES + nativeState.size + ASSISTANCE_BYTES +
            decisions.size * DECISION_BYTES + FOOTER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(MAGIC)
        output.put(VERSION.toByte())
        output.put(record.ruleVersion.toByte())
        output.put(record.difficulty.code.toByte())
        output.put(record.viewerSeat.toByte())
        output.put(record.dealerSeat.toByte())
        repeat(3) { output.put(0.toByte()) }
        output.putLong(record.seed)
        output.putLong(record.baseScore)
        output.putLong(record.nextChoiceSeed)
        output.putInt(nativeState.size)
        output.put(nativeState)
        output.putInt(record.assistance.undoUsed)
        output.putInt(record.assistance.hintUsed)
        output.putInt(decisions.size)
        for (decision in decisions) {
            output.putInt(decision.actionCount)
            output.putLong(decision.nextChoiceSeed)
        }
        output.putInt(crc(output.array(), output.position()))
        return output.array()
    }

    /** Structural checks precede native restore, which remains responsible for legal replay. */
    fun decode(bytes: ByteArray): LanzhouMahjongSessionRecord? {
        if (bytes.size !in (HEADER_BYTES + MIN_NATIVE_BYTES + FOOTER_BYTES)..MAX_BYTES) return null
        val ownedBytes = bytes.copyOf()
        val input = ByteBuffer.wrap(ownedBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (input.int != MAGIC) return null
        val version = input.unsignedByte()
        if (version !in 1..VERSION ||
            input.unsignedByte() != LanzhouMahjongSessionRecord.RULE_VERSION) return null
        val difficultyCode = input.unsignedByte()
        val difficulty = Difficulty.entries.firstOrNull { it.code == difficultyCode } ?: return null
        val viewer = input.unsignedByte()
        val dealer = input.unsignedByte()
        repeat(3) { if (input.unsignedByte() != 0) return null }
        val seed = input.long
        val base = input.long
        val nextChoiceSeed = input.long
        val nativeLength = input.int
        if (viewer !in 0..3 || dealer !in 0..3 || seed < 0 || nextChoiceSeed < 0 ||
            base !in 1..LanzhouMahjongSessionRecord.MAX_BASE_SCORE ||
            nativeLength !in MIN_NATIVE_BYTES..MAX_NATIVE_BYTES ||
            nativeLength > input.remaining() - FOOTER_BYTES) return null
        if (crc(ownedBytes, ownedBytes.size - FOOTER_BYTES) !=
            input.getInt(ownedBytes.size - FOOTER_BYTES)) return null
        val nativeState = ByteArray(nativeLength)
        input.get(nativeState)
        if (!validNativeEnvelope(nativeState, dealer, base)) return null
        return try {
            val assistance: LanzhouMahjongAssistanceUsage
            val decisions: List<LanzhouMahjongDecisionPoint>
            if (version == 1) {
                if (input.remaining() != FOOTER_BYTES) return null
                assistance = LanzhouMahjongAssistanceUsage(difficulty)
                decisions = emptyList()
            } else {
                if (input.remaining() < ASSISTANCE_BYTES + FOOTER_BYTES) return null
                assistance = LanzhouMahjongAssistanceUsage(difficulty, input.int, input.int)
                val count = input.int
                if (count !in 0..1000 || input.remaining() != count * DECISION_BYTES + FOOTER_BYTES) return null
                decisions = List(count) { LanzhouMahjongDecisionPoint(input.int, input.long) }
            }
            LanzhouMahjongSessionRecord(difficulty, viewer, seed, dealer, base,
                nextChoiceSeed, nativeState, assistance, decisions)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun crc(bytes: ByteArray, length: Int): Int =
        CRC32().apply { update(bytes, 0, length) }.value.toInt()
}

private const val MIN_NATIVE_BYTES = 130
private const val MAX_NATIVE_BYTES = 3130

private fun ByteBuffer.unsignedByte(): Int = get().toInt() and 0xff

/** LZMJ v1/v2 share a layout but pin different penalties; preserve bytes, never upgrade rules. */
internal fun validNativeEnvelope(bytes: ByteArray, dealer: Int, base: Long): Boolean {
    if (bytes.size !in MIN_NATIVE_BYTES..MAX_NATIVE_BYTES) return false
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (input.int != 0x4a4d5a4c || input.unsignedByte() !in 1..2 ||
        input.unsignedByte() != dealer || input.long != base) return false
    val actionCount = input.getInt(122).toLong() and 0xffff_ffffL
    if (actionCount > 1000 || bytes.size.toLong() != MIN_NATIVE_BYTES + actionCount * 3) return false
    var checksum = 0x811c9dc5.toInt()
    for (index in 0 until bytes.size - 4) {
        checksum = (checksum xor (bytes[index].toInt() and 0xff)) * 16777619
    }
    return checksum == input.getInt(bytes.size - 4)
}
