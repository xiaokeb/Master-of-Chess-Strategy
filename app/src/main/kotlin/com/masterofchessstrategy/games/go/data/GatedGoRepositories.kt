package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.games.go.*
import java.time.LocalDate

/** Freeze mutable inputs before waiting; a page's epoch never silently changes after restoration. */
internal class GatedGoSessionRepository(
    private val delegate: GoSessionRepository,
    private val gate: GoStorageEpochGate,
    private val ownerEpoch: Long = gate.epoch.value,
) : GoSessionRepository {
    override suspend fun load(): GoSavedSession? = gate.access(ownerEpoch) {
        delegate.load()?.let { it.copy(engineState = it.engineState.copyOf()) }
    }
    override suspend fun save(session: GoSavedSession) {
        val owned = session.copy(engineState = session.engineState.copyOf())
        gate.access(ownerEpoch) { delegate.save(owned) }
    }
    override suspend fun clear() = gate.access(ownerEpoch) { delegate.clear() }
}

internal class GatedGoLearningRepository(
    private val delegate: GoLearningProgressRepository,
    private val gate: GoStorageEpochGate,
    private val ownerEpoch: Long = gate.epoch.value,
) : GoLearningProgressRepository {
    override suspend fun load() = gate.access(ownerEpoch) { GoLearningProgressCodec.freeze(delegate.load()) }
    override suspend fun save(progress: GoLearningProgress) {
        val owned = GoLearningProgressCodec.freeze(progress)
        gate.access(ownerEpoch) { delegate.save(owned) }
    }
}

internal class GatedGoEndgameRepository(
    private val delegate: GoEndgameProgressRepository,
    private val gate: GoStorageEpochGate,
    private val ownerEpoch: Long = gate.epoch.value,
) : GoEndgameProgressRepository {
    override suspend fun load() = gate.access(ownerEpoch) { delegate.load() }
    override suspend fun select(id: String, mode: GoEndgameChallengeMode) = gate.access(ownerEpoch) { delegate.select(id, mode) }
    override suspend fun selectDaily(date: LocalDate, id: String) = gate.access(ownerEpoch) { delegate.selectDaily(date, id) }
    override suspend fun complete(id: String) = gate.access(ownerEpoch) { delegate.complete(id) }
}

/** GAI1 remains separate on disk, but its writes must respect the same replacement boundary. */
internal class GatedGoAiMatchRepository(
    private val delegate: GoAiMatchRepository,
    private val gate: GoStorageEpochGate,
    private val ownerEpoch: Long = gate.epoch.value,
) : GoAiMatchRepository {
    override suspend fun load() = gate.access(ownerEpoch) { delegate.load() }
    // GoAiSavedMatch already owns both byte arrays and exposes only copies.
    override suspend fun save(match: GoAiSavedMatch) = gate.access(ownerEpoch) { delegate.save(match) }
}

/** The gate is held across preparation AND the complete snapshot/replacement transaction. */
internal class GatedGoBackupRepository(
    private val delegate: LocalDataBackupRepository,
    private val gate: GoStorageEpochGate,
    private val preparation: GoBackupPreparation,
) : LocalDataBackupRepository {
    override suspend fun export(createdAtEpochMillis: Long): ByteArray = gate.backup(replacing = false) {
        preparation.beforeExport()
        delegate.export(createdAtEpochMillis)
    }
    override suspend fun restore(bytes: ByteArray): com.masterofchessstrategy.data.LocalDataRestoreSummary {
        val owned = bytes.copyOf()
        return gate.backup(replacing = true) {
            preparation.beforeRestore()
            delegate.restore(owned)
        }
    }
}
