package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import androidx.room.withTransaction
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongNativeHistory
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongSessionAdmission
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStorageEpochGate
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStoredSession
import java.io.IOException

/** All reads and writes for a formal creation or first terminal share the backup owner and a transaction. */
internal class RoomLanzhouMahjongSettlement(
    private val database: MocsDatabase,
    private val owner: LanzhouMahjongStorageEpochGate.Owner,
) {
    private val gate = LanzhouMahjongStorageEpochGate.forDatabase(database)
    private val active = database.activeGameDao()
    private val ledger = database.lanzhouMahjongSettlementDao()
    private val admission = LanzhouMahjongSessionAdmission()
    private val policy = LanzhouMahjongSettlementPolicy(LanzhouMahjongNativeSettlementReplay())
    private val gameCode = GameType.LANZHOU_MAHJONG.code

    /** [checkEligibility] must read the same database directly within the transaction. */
    suspend fun create(
        expected: ActiveGameEntity?,
        initial: LanzhouMahjongStoredSession,
        checkEligibility: suspend (Difficulty) -> Unit,
    ): ActiveGameEntity = gate.access(owner) {
        database.withTransaction {
            val row = admission.createRow(initial.record, initial.updatedAtEpochMillis)
            val sessionId = row.sessionId
            val current = active.find(gameCode)
            val existing = ledger.creation(sessionId)
            val creation = LanzhouMahjongFormalCreation(
                sessionId, LanzhouMahjongCreationOrigin.AUTHORIZED_STANDARD_HUMAN,
                initial.record, initial.updatedAtEpochMillis,
            )
            policy.validateCreationCandidate(creation)
            if (existing != null) {
                valid(current == row && existing.createdAt == creation.createdAt &&
                    existing.initialRecord.contentEquals(row.engineState) &&
                    existing.creationSha256 == creation.sha256 && ledger.receipt(sessionId) == null,
                    "该兰州麻将开局身份已使用")
                return@withTransaction row
            }
            valid(current == expected && current?.sessionId != sessionId, "兰州麻将活动局已变更")
            checkEligibility(initial.record.difficulty)
            ledger.insertCreation(LanzhouMahjongFormalCreationRow(
                sessionId, creation.createdAt, row.engineState, creation.sha256,
            ))
            writeActive(row)
            row
        }
    }

    suspend fun complete(
        expected: ActiveGameEntity,
        terminal: LanzhouMahjongStoredSession,
    ): LanzhouMahjongTerminalReceipt = gate.access(owner) {
        database.withTransaction {
            val row = admission.createRow(terminal.record, terminal.updatedAtEpochMillis)
            val current = active.find(gameCode)
            valid(current?.sessionId == row.sessionId && expected.sessionId == row.sessionId,
                "兰州麻将活动局身份已变更")
            val origin = ledger.creation(row.sessionId) ?: throw IOException("缺少兰州麻将正式创建凭据")
            val initial = LanzhouMahjongSessionRecordCodec.decode(origin.initialRecord)
                ?: throw IOException("兰州麻将创建记录损坏")
            val creation = LanzhouMahjongFormalCreation(
                row.sessionId, LanzhouMahjongCreationOrigin.AUTHORIZED_STANDARD_HUMAN, initial, origin.createdAt,
            )
            valid(origin.creationSha256 == creation.sha256, "兰州麻将创建记录摘要不一致")

            val existing = ledger.receipt(row.sessionId)
            val candidate = LanzhouMahjongTerminalCandidate(
                row.sessionId, terminal.record, existing?.capturedAt ?: terminal.updatedAtEpochMillis,
            )
            val result = (policy.firstTerminal(creation, candidate) as LanzhouMahjongFirstSettlement.Insert).receipt
            val stored = result.toRow()
            if (existing != null) {
                valid(current == row && existing.terminalRecord.contentEquals(stored.terminalRecord) &&
                    existing.copy(terminalRecord = stored.terminalRecord) == stored,
                    "该兰州麻将对局已有不同首结")
                return@withTransaction result
            }
            valid(current == expected, "兰州麻将活动局已变更")
            val previous = admission.admitRow(expected)
            val terminalPrefix = LanzhouMahjongNativeHistory.prefix(
                terminal.record.nativeState, expected.acceptedMoveCount,
            )
            valid(terminalPrefix?.contentEquals(previous.nativeState) == true,
                "兰州麻将终局与已保存动作历史不连续")
            ledger.insertReceipt(stored)
            writeActive(row)
            result
        }
    }

    private suspend fun writeActive(row: ActiveGameEntity) {
        active.upsert(row)
        valid(active.find(gameCode) == row, "兰州麻将活动局写入校验失败")
    }

    private fun LanzhouMahjongTerminalReceipt.toRow(): LanzhouMahjongTerminalReceiptRow {
        val scores = netScores
        return LanzhouMahjongTerminalReceiptRow(
            terminal.sessionId, terminal.capturedAt, terminal.copyRecordBytes(),
            outcome.name, winnerSeat, payerSeat, scores[0], scores[1], scores[2], scores[3],
        )
    }

    private fun valid(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }
}
