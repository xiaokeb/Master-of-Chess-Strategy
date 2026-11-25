package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongPublicView
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal enum class LanzhouMahjongCreationOrigin {
    AUTHORIZED_STANDARD_HUMAN, RESUME, BACKUP_IMPORT, LEGACY, PRACTICE, AUTO_PLAY,
}

/** A hash binds evidence; only the future committed creation ledger proves authorized origin. */
internal class LanzhouMahjongFormalCreation(
    val sessionId: String,
    val origin: LanzhouMahjongCreationOrigin,
    initial: LanzhouMahjongSessionRecord,
    val createdAt: Long,
    val version: Int = 1,
) {
    private val initialBytes = LanzhouMahjongSessionRecordCodec.encode(initial)
    val initial: LanzhouMahjongSessionRecord get() = decodeOwned(initialBytes)
    val sha256: String get() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use {
            it.writeUTF("MOCS-LZMJ-FORMAL-CREATION")
            it.writeInt(version)
            it.writeUTF(sessionId)
            it.writeUTF(origin.name)
            it.writeLong(createdAt)
            it.writeInt(initialBytes.size)
            it.write(initialBytes)
        }
        return settlementSha256(buffer.toByteArray())
    }
}

internal class LanzhouMahjongTerminalCandidate(
    val sessionId: String,
    record: LanzhouMahjongSessionRecord,
    val capturedAt: Long,
) {
    private val recordBytes = LanzhouMahjongSessionRecordCodec.encode(record)
    val record: LanzhouMahjongSessionRecord get() = decodeOwned(recordBytes)
    fun copyRecordBytes(): ByteArray = recordBytes.copyOf()
}

/** Never reinterpret a positive kong/penalty balance as a qualified win. */
internal enum class LanzhouMahjongQualifiedOutcome { WIN, LOSS, EXHAUSTIVE_DRAW, FALSE_DECLARATION }

internal class LanzhouMahjongTerminalReceipt(
    val creationSha256: String,
    val terminal: LanzhouMahjongTerminalCandidate,
    val difficulty: Difficulty,
    val viewerSeat: Int,
    val nativeRuleVersion: Int,
    val baseScore: Long,
    val outcome: LanzhouMahjongQualifiedOutcome,
    val winnerSeat: Int?,
    val payerSeat: Int?,
    scores: List<Long>,
    val version: Int = 1,
) {
    private val ownedScores = scores.toList()
    val netScores: List<Long> get() = ownedScores.toList()
    val isQualifiedWin: Boolean get() = outcome == LanzhouMahjongQualifiedOutcome.WIN
}

internal sealed interface LanzhouMahjongFirstSettlement {
    /** A proposal only. No qualification or rewards exist until the enclosing transaction commits. */
    data class Insert(val receipt: LanzhouMahjongTerminalReceipt) : LanzhouMahjongFirstSettlement
    data class AlreadySettled(val receipt: LanzhouMahjongTerminalReceipt) : LanzhouMahjongFirstSettlement
}

/** Fresh Native initial state, canonical complete replay and snapshot must come from one session. */
internal interface LanzhouMahjongSettlementReplay {
    fun sessionId(record: LanzhouMahjongSessionRecord): String
    fun replay(record: LanzhouMahjongSessionRecord): LanzhouMahjongSettlementReplayResult
}

internal class LanzhouMahjongSettlementReplayResult(
    initialState: ByteArray,
    canonicalState: ByteArray,
    val view: LanzhouMahjongPublicView,
) {
    private val initial = initialState.copyOf()
    private val canonical = canonicalState.copyOf()
    fun copyInitialState(): ByteArray = initial.copyOf()
    fun copyCanonicalState(): ByteArray = canonical.copyOf()
}

private fun decodeOwned(bytes: ByteArray): LanzhouMahjongSessionRecord =
    checkNotNull(LanzhouMahjongSessionRecordCodec.decode(bytes.copyOf()))

private fun settlementSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
