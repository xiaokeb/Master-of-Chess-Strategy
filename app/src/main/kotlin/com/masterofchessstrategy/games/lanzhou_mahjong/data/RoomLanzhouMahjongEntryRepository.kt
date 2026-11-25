package com.masterofchessstrategy.games.lanzhou_mahjong.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.LanzhouMahjongPhase
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec
import com.masterofchessstrategy.games.lanzhou_mahjong.settlement.LanzhouMahjongNativeSettlementReplay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A single gate/Room snapshot. Never nest another repository's non-reentrant gate access. */
internal class RoomLanzhouMahjongEntryRepository internal constructor(
    private val learningDao: LanzhouMahjongLearningProgressDao,
    private val activeDao: ActiveGameDao,
    private val gate: LanzhouMahjongStorageEpochGate,
    private val owner: LanzhouMahjongStorageEpochGate.Owner,
    private val sessionAdmission: LanzhouMahjongSessionAdmission,
    private val dispatcher: CoroutineDispatcher,
    private val transaction: suspend (suspend () -> LanzhouMahjongEntryFacts) -> LanzhouMahjongEntryFacts,
    private val readTrustedWins: suspend () -> Map<Difficulty, Int> = { emptyMap() },
    private val isCompleted: suspend (ActiveGameEntity, LanzhouMahjongSessionRecord) -> Boolean = { _, _ -> false },
) : LanzhouMahjongEntryRepository {
    constructor(
        database: MocsDatabase,
        gate: LanzhouMahjongStorageEpochGate = LanzhouMahjongStorageEpochGate.forDatabase(database),
        owner: LanzhouMahjongStorageEpochGate.Owner = gate.captureOwner(),
    ) : this(database.lanzhouMahjongLearningProgressDao(), database.activeGameDao(), gate, owner,
        LanzhouMahjongSessionAdmission(), Dispatchers.IO, { read -> database.withTransaction { read() } },
        { database.readLanzhouMahjongTrustedWins() },
        { row, record -> database.isLanzhouMahjongCompleted(row, record) }) {
        require(gate === LanzhouMahjongStorageEpochGate.forDatabase(database))
    }

    override val ownerEpoch: Long get() = owner.epoch
    override val epoch get() = gate.epoch

    override suspend fun loadFacts(): LanzhouMahjongEntryFacts = gate.access(owner) {
        withContext(dispatcher) { transaction { readFacts() } }
    }

    override suspend fun authorizeNew(difficulty: Difficulty, expectedCurrent: LanzhouMahjongStoredSession?) {
        gate.access(owner) {
            withContext(dispatcher) {
                transaction {
                    val facts = readFacts()
                    if (!sameCheckpoint(facts.active, expectedCurrent)) deny(LanzhouMahjongEntryDenial.ACTIVE_GAME_CHANGED)
                    requireLanzhouMahjongCreationEligibility(facts, difficulty)
                    currentCoroutineContext().ensureActive()
                    facts
                }
            }
        }
    }

    private suspend fun readFacts(): LanzhouMahjongEntryFacts {
        currentCoroutineContext().ensureActive()
        val learning = decodeLanzhouLearningRows(learningDao.readBoundedRows())
        val row = activeDao.find(GameType.LANZHOU_MAHJONG.code)
        val active = row?.let { LanzhouMahjongStoredSession(sessionAdmission.admitRow(it), it.updatedAtEpochMillis) }
        currentCoroutineContext().ensureActive()
        return LanzhouMahjongEntryFacts(learning, active, readTrustedWins(),
            row != null && active != null && isCompleted(row, active.record))
    }

    private fun sameCheckpoint(a: LanzhouMahjongStoredSession?, b: LanzhouMahjongStoredSession?): Boolean =
        if (a == null || b == null) a == null && b == null
        else a.updatedAtEpochMillis == b.updatedAtEpochMillis &&
            LanzhouMahjongSessionRecordCodec.encode(a.record).contentEquals(LanzhouMahjongSessionRecordCodec.encode(b.record))

    private fun deny(reason: LanzhouMahjongEntryDenial): Nothing = throw LanzhouMahjongEntryDeniedException(reason)
}

private suspend fun MocsDatabase.isLanzhouMahjongCompleted(
    row: ActiveGameEntity,
    record: LanzhouMahjongSessionRecord,
): Boolean {
    val ledger = lanzhouMahjongSettlementDao()
    val receipt = ledger.receipt(row.sessionId)
    if (receipt != null) return receipt.terminalRecord.contentEquals(row.engineState)
    if (ledger.creation(row.sessionId) != null) return false
    val phase = LanzhouMahjongNativeSettlementReplay().replay(record).view.phase
    return phase == LanzhouMahjongPhase.WON || phase == LanzhouMahjongPhase.EXHAUSTIVE_DRAW
}

/** Called directly by the production writer inside its existing Room transaction. */
internal fun requireLanzhouMahjongCreationEligibility(facts: LanzhouMahjongEntryFacts, difficulty: Difficulty) {
    if (!facts.tutorialCompleted) throw LanzhouMahjongEntryDeniedException(LanzhouMahjongEntryDenial.TUTORIAL_REQUIRED)
    if (!facts.canStart(difficulty)) {
        throw LanzhouMahjongEntryDeniedException(LanzhouMahjongEntryDenial.TRUSTED_WINS_UNAVAILABLE)
    }
}

/** Receipts have been replayed at commit or restore; join them to their original seat and difficulty. */
internal suspend fun MocsDatabase.readLanzhouMahjongTrustedWins(): Map<Difficulty, Int> =
    lanzhouMahjongSettlementDao().qualifiedWinSources().groupingBy { source ->
        val initial = LanzhouMahjongSessionRecordCodec.decode(source.initialRecord)
            ?: throw java.io.IOException("兰州麻将可信胜场的创建记录损坏")
        if (initial.viewerSeat != source.winnerSeat ||
            LanzhouMahjongSessionAdmission.sessionId(initial) != source.sessionId) {
            throw java.io.IOException("兰州麻将可信胜场的创建身份不一致")
        }
        initial.difficulty
    }.eachCount()
