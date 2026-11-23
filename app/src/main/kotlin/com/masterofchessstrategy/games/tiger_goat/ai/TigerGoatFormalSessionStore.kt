package com.masterofchessstrategy.games.tiger_goat.ai

import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatStorageEpochGate

internal sealed interface TigerGoatFormalLoadResult {
    data object Missing : TigerGoatFormalLoadResult
    /** Existing local MOCT belongs to the local entry; never reinterpret or overwrite it on load. */
    data object LocalGame : TigerGoatFormalLoadResult
    data object Incompatible : TigerGoatFormalLoadResult
    data class Loaded(val checkpoint: TigerGoatFormalCheckpoint) : TigerGoatFormalLoadResult
}

/** Room/backup adapters must preserve metadata and MOCT atomically, under the same gate. */
internal interface TigerGoatFormalSessionStore {
    val isCurrent: Boolean
    suspend fun load(): TigerGoatFormalLoadResult
    suspend fun save(checkpoint: TigerGoatFormalCheckpoint)
}

/** One owner per page. Delegate is raw storage; wrapping an already gated store would reenter. */
internal class GatedTigerGoatFormalSessionStore(
    private val delegate: TigerGoatFormalSessionStore,
    private val gate: TigerGoatStorageEpochGate,
) : TigerGoatFormalSessionStore {
    private val owner = gate.captureOwner()
    override val isCurrent: Boolean get() = delegate.isCurrent && gate.epoch.value == owner.epoch

    override suspend fun load(): TigerGoatFormalLoadResult = gate.access(owner) {
        when (val loaded = delegate.load()) {
            is TigerGoatFormalLoadResult.Loaded ->
                TigerGoatFormalLoadResult.Loaded(loaded.checkpoint.copyOwned())
            else -> loaded
        }
    }

    override suspend fun save(checkpoint: TigerGoatFormalCheckpoint) {
        val owned = checkpoint.copyOwned()
        gate.access(owner) { delegate.save(owned) }
    }
}
