package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One captured epoch for normal checkpoint access, explicit creation and its acknowledgement. */
internal class RoomGoFormalAiMatchRepository(
    private val database: MocsDatabase,
    checkpoints: GoAiMatchRepository,
    learning: GoLearningProgressRepository,
    private val gate: GoStorageEpochGate,
    private val ownerEpoch: Long = gate.epoch.value,
) : GoAiFormalMatchRepository {
    private val delegate = GatedGoAiMatchRepository(checkpoints, gate, ownerEpoch)
    private val creator = RoomGoRankedMatchCreator(database, checkpoints, learning, gate, ownerEpoch)
    override suspend fun load() = delegate.load()
    override suspend fun save(match: GoAiSavedMatch) = delegate.save(match)
    override suspend fun createStandardMatch(match: GoAiSavedMatch) { creator.create(match) }

    override suspend fun confirmsStandardCreation(match: GoAiSavedMatch): Boolean = gate.access(ownerEpoch) {
        val expected = withContext(Dispatchers.Default) { admitGoRankedStart(match, 0) }
        database.withTransaction {
            val start = database.goRankedDao().start(expected.recordId)?.toStart() ?: return@withTransaction false
            val active = database.goAiCheckpointDao().findActive(GameType.GO.code)
                ?.let(GoAiCheckpointRows::entry) ?: return@withTransaction false
            start.copyBytes().contentEquals(expected.copyBytes()) &&
                active.copyBytes().contentEquals(expected.copyBytes()) &&
                active.lineage == GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION)
        }
    }
}
