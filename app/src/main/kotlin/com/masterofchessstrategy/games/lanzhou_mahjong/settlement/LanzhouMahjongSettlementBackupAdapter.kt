package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.GameBackupDescriptor
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.GameBackupSnapshotGuard
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.LanzhouMahjongPhase
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongBackupAdapter

/** Each ledger record owns a creation and at most one dependent receipt. */
internal class LanzhouMahjongSettlementBackupAdapter : GameBackupAdapter, GameBackupSnapshotGuard {
    private val native = LanzhouMahjongNativeSettlementReplay()
    private val policy = LanzhouMahjongSettlementPolicy(native)
    override val descriptor = GameBackupDescriptor(KIND, 5, SLOT, 200_000, 45, MAX_WIRE_BYTES)

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> {
        val dao = database.lanzhouMahjongSettlementDao()
        val receipts = dao.readReceipts().associateBy { it.sessionId }.toMutableMap()
        val records = dao.readCreations().map { encode(it, receipts.remove(it.sessionId)) }
        require(receipts.isEmpty()) { "兰州麻将首结缺少开局凭据" }
        return records
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        decode(record)
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
        val (start, receipt) = decode(record)
        val creation = LanzhouMahjongFormalCreation(
            start.sessionId, LanzhouMahjongCreationOrigin.AUTHORIZED_STANDARD_HUMAN,
            requireNotNull(LanzhouMahjongSessionRecordCodec.decode(start.initialRecord)), start.createdAt,
        )
        require(creation.sha256 == start.creationSha256) { "兰州麻将开局凭据摘要不匹配" }
        if (receipt == null) {
            policy.validateCreationCandidate(creation)
        } else {
            val terminal = LanzhouMahjongTerminalCandidate(
                start.sessionId, requireNotNull(LanzhouMahjongSessionRecordCodec.decode(receipt.terminalRecord)),
                receipt.capturedAt,
            )
            val verified = (policy.firstTerminal(creation, terminal) as LanzhouMahjongFirstSettlement.Insert).receipt
            require(receipt.sameEvidence(verified.toLanzhouMahjongReceiptRow())) { "兰州麻将四席结算与原生回放不一致" }
        }
        return PreparedLedger(start, receipt)
    }

    override fun validatePreparedSnapshot(records: List<PreparedGameBackup>) {
        val ledgers = records.filterIsInstance<PreparedLedger>()
        val byId = ledgers.associateBy { it.start.sessionId }
        require(byId.size == ledgers.size) { "兰州麻将备份包含重复开局身份" }
        val active = records.filterIsInstance<LanzhouMahjongBackupAdapter.PreparedLanzhouMahjongBackup>()
            .singleOrNull()?.activeRow() ?: return
        val linked = byId[active.sessionId] ?: return // Old activity remains playable without qualification.
        if (linked.receipt != null) {
            require(active.engineState.contentEquals(linked.receipt.terminalRecord)) {
                "兰州麻将首结与活动局终局不一致"
            }
        } else {
            val initial = requireNotNull(LanzhouMahjongSessionRecordCodec.decode(linked.start.initialRecord))
            val ongoing = requireNotNull(LanzhouMahjongSessionRecordCodec.decode(active.engineState))
            val replay = native.replay(ongoing)
            require(initial.nativeState.contentEquals(replay.copyInitialState()) &&
                replay.view.phase !in setOf(LanzhouMahjongPhase.WON, LanzhouMahjongPhase.EXHAUSTIVE_DRAW)) {
                "兰州麻将活动局缺少对应首结或与正式初态不一致"
            }
        }
    }

    private class PreparedLedger(
        val start: LanzhouMahjongFormalCreationRow,
        val receipt: LanzhouMahjongTerminalReceiptRow?,
    ) : PreparedGameBackup {
        override val storageSlot = SLOT
        override suspend fun write(database: MocsDatabase) {
            val dao = database.lanzhouMahjongSettlementDao()
            dao.insertCreation(start)
            receipt?.let { dao.insertReceipt(it) }
        }
    }

    companion object {
        const val KIND = "LANZHOU_MAHJONG_SETTLEMENT"
        val SLOT = BackupStorageSlot(BackupStorageTable.LANZHOU_MAHJONG_SETTLEMENT, GameType.LANZHOU_MAHJONG)
        private const val MAX_WIRE_BYTES = 131_072
        private const val HEX = "0123456789abcdef"
        private val identity = Regex("lzmj-[0-9a-f]{64}")
        private val digest = Regex("[0-9a-f]{64}")

        private fun encode(start: LanzhouMahjongFormalCreationRow, receipt: LanzhouMahjongTerminalReceiptRow?) =
            GameBackupRecord(KIND, buildList {
                addAll(listOf("3", "1", start.sessionId, start.createdAt.toString(), start.creationSha256,
                    hex(start.initialRecord)))
                if (receipt != null) addAll(listOf(
                    receipt.capturedAt.toString(), hex(receipt.terminalRecord), receipt.outcome,
                    receipt.winnerSeat?.toString() ?: "-", receipt.payerSeat?.toString() ?: "-",
                    receipt.seat0Score.toString(), receipt.seat1Score.toString(),
                    receipt.seat2Score.toString(), receipt.seat3Score.toString(),
                ))
                if (receipt != null && receipt.archiveMark != LanzhouMahjongArchiveMark.NORMAL.code) {
                    add(receipt.archiveMark.toString())
                }
            })

        private fun decode(record: GameBackupRecord): Pair<LanzhouMahjongFormalCreationRow, LanzhouMahjongTerminalReceiptRow?> {
            val f = record.fields
            require(record.kind == KIND && f.size in setOf(6, 15, 16) && record.encodedSizeBytes <= MAX_WIRE_BYTES)
            require(f[0] == "3" && f[1] == "1" && identity.matches(f[2]) && digest.matches(f[4]))
            val start = LanzhouMahjongFormalCreationRow(f[2], timestamp(f[3]), recordBytes(f[5]), f[4])
            if (f.size == 6) return start to null
            require(LanzhouMahjongQualifiedOutcome.entries.any { it.name == f[8] })
            val archiveMark = f.getOrNull(15)?.let { encoded ->
                requireNotNull(LanzhouMahjongArchiveMark.entries.firstOrNull {
                    it != LanzhouMahjongArchiveMark.NORMAL && it.code.toString() == encoded
                }).code
            } ?: LanzhouMahjongArchiveMark.NORMAL.code
            val receipt = LanzhouMahjongTerminalReceiptRow(
                f[2], timestamp(f[6]), recordBytes(f[7]), f[8], seat(f[9]), seat(f[10]),
                number(f[11]), number(f[12]), number(f[13]), number(f[14]), archiveMark,
            )
            return start to receipt
        }

        private fun number(value: String): Long =
            requireNotNull(value.toLongOrNull()).also { require(it.toString() == value) }

        private fun timestamp(value: String): Long = number(value).also { require(it >= 0) }
        private fun seat(value: String): Int? =
            if (value == "-") null else number(value).also { require(it in 0L..3L) }.toInt()

        private fun recordBytes(value: String): ByteArray {
            require(value.length % 2 == 0 && value.length / 2 in 1..LanzhouMahjongSessionRecordCodec.MAX_BYTES &&
                value.all { it in HEX })
            val bytes = ByteArray(value.length / 2) { index ->
                ((HEX.indexOf(value[2 * index]) shl 4) or HEX.indexOf(value[2 * index + 1])).toByte()
            }
            val decoded = requireNotNull(LanzhouMahjongSessionRecordCodec.decode(bytes))
            require(bytes.contentEquals(LanzhouMahjongSessionRecordCodec.encode(decoded))) {
                "兰州麻将账本需要规范棋谱"
            }
            return bytes
        }

        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append(HEX[(byte.toInt() ushr 4) and 15])
                append(HEX[byte.toInt() and 15])
            }
        }
    }
}
