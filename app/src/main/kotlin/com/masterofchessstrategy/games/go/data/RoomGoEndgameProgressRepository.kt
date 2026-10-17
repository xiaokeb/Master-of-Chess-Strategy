package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.go.GoEndgameChallengeMode
import com.masterofchessstrategy.games.go.GoEndgameProgress
import com.masterofchessstrategy.games.go.GoEndgameProgressCodec
import com.masterofchessstrategy.games.go.GoEndgameProgressRepository
import java.time.LocalDate

/** Reuses GEP1's contiguous unlock and fixed-daily semantics without granting shared rewards. */
internal class RoomGoEndgameProgressRepository(
    store: GoProgressStore,
    legacy: GoEndgameLegacySource? = null,
    clock: () -> Long = System::currentTimeMillis,
) : GoEndgameProgressRepository {
    constructor(database: MocsDatabase, legacy: GoEndgameLegacySource? = null) : this(RoomGoProgressStore(database), legacy)
    private val access = GoProgressAccess(store, GoProgressKind.ENDGAME,
        { GoEndgameProgressCodec.encode(legacy?.readCommitted() ?: GoEndgameProgress()) }, clock)

    override suspend fun load() = checkNotNull(GoEndgameProgressCodec.decode(access.load()))
    override suspend fun select(id: String, mode: GoEndgameChallengeMode) = update { it.select(id, mode) }
    override suspend fun selectDaily(date: LocalDate, id: String) = update { it.selectDaily(date, id) }
    override suspend fun complete(id: String) = update { it.complete(id) }
    suspend fun clear() = access.clear()

    private suspend fun update(transform: (GoEndgameProgress) -> GoEndgameProgress): GoEndgameProgress =
        checkNotNull(GoEndgameProgressCodec.decode(access.update {
            GoEndgameProgressCodec.encode(transform(checkNotNull(GoEndgameProgressCodec.decode(it))))
        }))
}
