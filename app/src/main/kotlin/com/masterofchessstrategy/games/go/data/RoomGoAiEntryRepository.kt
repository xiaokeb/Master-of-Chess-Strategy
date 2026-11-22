package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
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
    private val clock: () -> Long = System::currentTimeMillis,
    beforeCommit: suspend () -> Unit = {},
    private val newGameLimitMinutes: Int? = null,
    private val matchTime: () -> Long = clock,
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
        opening: GoAiOpening?,
    ): GoAiSavedMatch = gate.access(ownerEpoch) {
        val match = withContext(worker) {
            val id = UUID.randomUUID().toString()
            if (opening == null) {
                NativeGoEngine(config.boardSize, if (config.handicapStones == 0) 15 else 0, config.handicapStones).use {
                    GoAiSavedMatch(id, config, it.serialize(), clock = newClock(config, GoPlayer.entries[it.currentPlayer.value]))
                }
            } else {
                require(config == opening.config(humanPlayer = null, difficulty = config.difficulty,
                    blind = config.blind, perMoveLimitSeconds = config.perMoveLimitSeconds))
                val bytes = opening.copyBytes()
                opening.createEngine().use {
                    GoAiSavedMatch(id, config, bytes, openingBytes = bytes,
                        clock = newClock(config, GoPlayer.entries[it.currentPlayer.value]))
                }
            }
        }
        database.withTransaction {
            creator.createEntryInTransaction(match, expectedActive, abandonOngoing)
            match
        }
    }

    private fun newClock(config: GoAiMatchConfig, first: GoPlayer): GoTurnClock? {
        if (config.streak != null) return null
        return config.perMoveLimitSeconds?.let {
            GoTurnClock.start(it * 1_000L, first, matchTime(), GoClockMode.PER_MOVE)
        } ?: newGameLimitMinutes?.let { GoTurnClock.start(it * 60_000L, first, matchTime()) }
    }
}
