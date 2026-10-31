package com.masterofchessstrategy.games.tiger_goat.data

import com.masterofchessstrategy.games.tiger_goat.TigerGoatLoadResult
import com.masterofchessstrategy.games.tiger_goat.TigerGoatSavedSession
import com.masterofchessstrategy.games.tiger_goat.TigerGoatSessionStore
import kotlinx.coroutines.flow.StateFlow

/** Adds fixed-owner protection to the existing local store without changing its envelope. */
internal class GatedTigerGoatSessionStore(
    private val delegate: TigerGoatSessionStore,
    private val gate: TigerGoatStorageEpochGate,
    private val owner: TigerGoatStorageEpochGate.Owner = gate.captureOwner(),
) : TigerGoatSessionStore {
    val ownerEpoch: Long get() = owner.epoch
    val epoch: StateFlow<Long> get() = gate.epoch

    override suspend fun load(): TigerGoatLoadResult = gate.access(owner) {
        when (val result = delegate.load()) {
            is TigerGoatLoadResult.Loaded -> TigerGoatLoadResult.Loaded(result.saved.copyOwned())
            TigerGoatLoadResult.Missing -> TigerGoatLoadResult.Missing
            TigerGoatLoadResult.Incompatible -> TigerGoatLoadResult.Incompatible
        }
    }

    override suspend fun save(saved: TigerGoatSavedSession) {
        // Own mutable bytes before waiting; caller edits must not alter a queued save.
        val owned = saved.copyOwned()
        gate.access(owner) { delegate.save(owned) }
    }
}
