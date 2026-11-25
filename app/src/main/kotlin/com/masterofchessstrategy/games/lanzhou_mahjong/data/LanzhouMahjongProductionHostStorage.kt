package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongHostStorage
import com.masterofchessstrategy.games.lanzhou_mahjong.settlement.RoomLanzhouMahjongSettlement

/** Bind the DAO, fixed writer owner and backup epoch to the exact same database. */
internal fun MocsDatabase.createLanzhouMahjongHostStorage(): LanzhouMahjongHostStorage {
    val database = this
    val gate = LanzhouMahjongStorageEpochGate.forDatabase(database)
    return LanzhouMahjongHostStorage(gate) { owner ->
        val store = RoomLanzhouMahjongAdmittedSessionStore(database, database.activeGameDao(), gate, owner)
        val entry = RoomLanzhouMahjongEntryRepository(database, gate, owner)
        val settlement = RoomLanzhouMahjongSettlement(database, owner)
        object : LanzhouMahjongAdmittedSessionStore by store {
            override val persistsFormalCreation = true

            override suspend fun canReplaceCompleted(snapshot: LanzhouMahjongStoredSession): Boolean {
                val facts = entry.loadFacts()
                val current = facts.active ?: return false
                return facts.activeCompleted && current.updatedAtEpochMillis == snapshot.updatedAtEpochMillis &&
                    LanzhouMahjongSessionAdmission.sessionId(current.record) ==
                    LanzhouMahjongSessionAdmission.sessionId(snapshot.record)
            }

            override suspend fun authorizeNew(difficulty: com.masterofchessstrategy.engine.Difficulty,
                expectedCurrent: LanzhouMahjongStoredSession?) = entry.authorizeNew(difficulty, expectedCurrent)

            override suspend fun createFormal(initial: LanzhouMahjongStoredSession, expected: LanzhouMahjongStoredSession?) {
                settlement.createFromHost(expected, initial) { difficulty ->
                    val learning = decodeLanzhouLearningRows(database.lanzhouMahjongLearningProgressDao().readBoundedRows())
                    val facts = LanzhouMahjongEntryFacts(learning, null, database.readLanzhouMahjongTrustedWins())
                    requireLanzhouMahjongCreationEligibility(facts, difficulty)
                }
            }

            override suspend fun saveCheckpoint(
                snapshot: LanzhouMahjongStoredSession, expected: LanzhouMahjongStoredSession?, terminal: Boolean,
            ) {
                settlement.saveFromHost(expected, snapshot, terminal)
            }
        }
    }
}
