package com.masterofchessstrategy.games.lanzhou_mahjong.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec
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
) : LanzhouMahjongEntryRepository {
    constructor(
        database: MocsDatabase,
        gate: LanzhouMahjongStorageEpochGate = LanzhouMahjongStorageEpochGate.forDatabase(database),
        owner: LanzhouMahjongStorageEpochGate.Owner = gate.captureOwner(),
    ) : this(database.lanzhouMahjongLearningProgressDao(), database.activeGameDao(), gate, owner,
        LanzhouMahjongSessionAdmission(), Dispatchers.IO, { read -> database.withTransaction { read() } }) {
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
                    if (!facts.tutorialCompleted) deny(LanzhouMahjongEntryDenial.TUTORIAL_REQUIRED)
                    if (difficulty != Difficulty.EASY) deny(LanzhouMahjongEntryDenial.TRUSTED_WINS_UNAVAILABLE)
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
        return LanzhouMahjongEntryFacts(learning, active)
    }

    private fun sameCheckpoint(a: LanzhouMahjongStoredSession?, b: LanzhouMahjongStoredSession?): Boolean =
        if (a == null || b == null) a == null && b == null
        else a.updatedAtEpochMillis == b.updatedAtEpochMillis &&
            LanzhouMahjongSessionRecordCodec.encode(a.record).contentEquals(LanzhouMahjongSessionRecordCodec.encode(b.record))

    private fun deny(reason: LanzhouMahjongEntryDenial): Nothing = throw LanzhouMahjongEntryDeniedException(reason)
}
