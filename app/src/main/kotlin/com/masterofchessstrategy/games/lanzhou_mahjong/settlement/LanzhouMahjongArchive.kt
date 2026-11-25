package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import androidx.room.withTransaction
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.LanzhouMahjongPublicView
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongNativeHistory
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStorageEpochGate
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LanzhouMahjongArchiveItem(
    val id: String,
    val capturedAt: Long,
    val difficulty: Difficulty,
    val outcome: LanzhouMahjongQualifiedOutcome,
)

internal data class LanzhouMahjongArchivePage(val items: List<LanzhouMahjongArchiveItem>, val hasNext: Boolean)
internal data class LanzhouMahjongReplayMove(val seat: Int, val kind: LanzhouMahjongActionKind, val tile: Int?)
internal data class LanzhouMahjongReplayFrame(
    val index: Int,
    val actionCount: Int,
    val viewerSeat: Int,
    val move: LanzhouMahjongReplayMove?,
    val view: LanzhouMahjongPublicView,
)

/** A fixed storage owner ensures requests from before a restore cannot read the replacement. */
internal class LanzhouMahjongArchive(private val database: MocsDatabase) {
    private val gate = LanzhouMahjongStorageEpochGate.forDatabase(database)
    private val owner = gate.captureOwner()
    val ownerEpoch: Long get() = owner.epoch
    val epoch get() = gate.epoch
    private val dao = database.lanzhouMahjongSettlementDao()
    private val native = LanzhouMahjongNativeSettlementReplay()
    private val policy = LanzhouMahjongSettlementPolicy(native)

    suspend fun page(index: Int): LanzhouMahjongArchivePage = read {
        require(index >= 0)
        val rows = dao.archivePage(PAGE_SIZE + 1, index.toLong() * PAGE_SIZE)
        LanzhouMahjongArchivePage(rows.take(PAGE_SIZE).map { row ->
            val initial = decode(row.initialRecord)
            if (native.sessionId(initial) != row.sessionId || row.capturedAt < 0) {
                throw IOException("兰州麻将棋谱身份不一致")
            }
            val outcome = LanzhouMahjongQualifiedOutcome.entries.firstOrNull { it.name == row.outcome }
                ?: throw IOException("兰州麻将棋谱结算类型无效")
            LanzhouMahjongArchiveItem(row.sessionId, row.capturedAt, initial.difficulty, outcome)
        }, rows.size > PAGE_SIZE)
    }

    suspend fun frame(id: String, index: Int): LanzhouMahjongReplayFrame = read {
        val start = dao.creation(id) ?: throw IOException("找不到兰州麻将棋谱")
        val receipt = dao.receipt(id) ?: throw IOException("找不到兰州麻将首结")
        val creation = LanzhouMahjongFormalCreation(
            id, LanzhouMahjongCreationOrigin.AUTHORIZED_STANDARD_HUMAN, decode(start.initialRecord), start.createdAt,
        )
        val record = decode(receipt.terminalRecord)
        val candidate = LanzhouMahjongTerminalCandidate(id, record, receipt.capturedAt)
        val verified = (policy.firstTerminal(creation, candidate) as LanzhouMahjongFirstSettlement.Insert).receipt
        if (creation.sha256 != start.creationSha256 || !receipt.sameEvidence(verified.toLanzhouMahjongReceiptRow())) {
            throw IOException("兰州麻将棋谱与正式收据不一致")
        }
        val bytes = record.nativeState
        val count = LanzhouMahjongNativeHistory.actionCount(bytes) ?: throw IOException("兰州麻将动作记录无效")
        require(index in 0..count)
        val prefix = if (index == count) bytes else checkNotNull(LanzhouMahjongNativeHistory.prefix(bytes, index))
        NativeLanzhouMahjongSession(record.seed, record.dealerSeat, record.baseScore, record.viewerSeat).use { session ->
            if (session.restore(prefix) != RestoreResult.Restored || !session.serialize().contentEquals(prefix)) {
                throw IOException("兰州麻将棋谱步骤无法恢复")
            }
            LanzhouMahjongReplayFrame(index, count, record.viewerSeat, move(bytes, index, record.viewerSeat), session.snapshot())
        }
    }

    private suspend fun <T> read(block: suspend () -> T): T = gate.access(owner) {
        withContext(Dispatchers.IO) { database.withTransaction { block() } }
    }

    private fun decode(bytes: ByteArray): LanzhouMahjongSessionRecord =
        LanzhouMahjongSessionRecordCodec.decode(bytes) ?: throw IOException("兰州麻将棋谱记录损坏")

    private fun move(bytes: ByteArray, index: Int, viewerSeat: Int): LanzhouMahjongReplayMove? {
        if (index == 0) return null
        val offset = 126 + (index - 1) * 3
        val kind = LanzhouMahjongActionKind.entries.first { it.code == (bytes[offset].toInt() and 255) }
        val seat = bytes[offset + 1].toInt() and 255
        // Draw and win metadata are not needed to show the action. Hidden kongs keep the original viewer's visibility.
        val showsTile = kind in publicTileActions || (kind == LanzhouMahjongActionKind.CONCEALED_KONG && seat == viewerSeat)
        val tile = (bytes[offset + 2].toInt() and 255).takeIf { showsTile && it in 0..26 }
        return LanzhouMahjongReplayMove(seat, kind, tile)
    }

    companion object {
        private const val PAGE_SIZE = 20
        private val publicTileActions = setOf(
            LanzhouMahjongActionKind.DISCARD, LanzhouMahjongActionKind.READY_DISCARD, LanzhouMahjongActionKind.PUNG,
            LanzhouMahjongActionKind.EXPOSED_KONG, LanzhouMahjongActionKind.ADDED_KONG,
        )
    }
}
