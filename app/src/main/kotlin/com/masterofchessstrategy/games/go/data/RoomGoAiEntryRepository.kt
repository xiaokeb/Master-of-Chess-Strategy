package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.games.go.*
import java.util.UUID
import kotlinx.coroutines.*

/** Setup owns one captured epoch. Supplied checkpoint/learning repositories must be ungated. */
internal class RoomGoAiEntryRepository(
    private val database: MocsDatabase,
    private val checkpoints: GoAiMatchRepository,
    learning: GoLearningProgressRepository,
    private val gate: GoStorageEpochGate = GoStorageEpochGate.forDatabase(database),
    private val ownerEpoch: Long = gate.epoch.value,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    clock: () -> Long = System::currentTimeMillis,
    beforeCommit: suspend () -> Unit = {},
) : GoAiEntryRepository {
    private val creator = RoomGoRankedMatchCreator(database, checkpoints, learning, gate, ownerEpoch,
        worker, clock, beforeCommit)
    override val isCurrent: Boolean get() = gate.epoch.value == ownerEpoch

    override suspend fun load(): GoAiEntrySnapshot = gate.access(ownerEpoch) {
        database.withTransaction {
            val active = checkpoints.load()
            val admitted = active?.let { withContext(worker) { admitGoAiCheckpoint(it) } }
            val stored = admitted != null && admitted.phase == GoPhase.FINISHED && active.completionReserved &&
                database.goAiProjectionDao().find(admitted.recordId) == GoAiProjectionEntity(admitted.recordId)
            GoAiEntrySnapshot(active, admitted?.phase, stored, creator.eligibilityInTransaction())
        }
    }

    override suspend fun create(
        config: GoAiMatchConfig,
        expectedActive: GoAiSavedMatch?,
        abandonOngoing: Boolean,
    ): GoAiSavedMatch = gate.access(ownerEpoch) {
        val match = withContext(worker) {
            NativeGoEngine(config.boardSize, if (config.handicapStones == 0) 15 else 0, config.handicapStones).use {
                GoAiSavedMatch(UUID.randomUUID().toString(), config, it.serialize())
            }
        }
        database.withTransaction {
            creator.createEntryInTransaction(match, expectedActive, abandonOngoing)
            match
        }
    }
}
