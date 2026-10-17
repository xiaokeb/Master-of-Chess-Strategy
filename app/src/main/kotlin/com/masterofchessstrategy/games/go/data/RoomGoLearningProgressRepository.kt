package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.go.GoLearningProgress
import com.masterofchessstrategy.games.go.GoLearningProgressRepository

/** Detailed lessons merge monotonically; no synthetic four-step row or ranked reward is written. */
internal class RoomGoLearningProgressRepository(
    store: GoProgressStore,
    legacy: GoLearningLegacySource? = null,
    clock: () -> Long = System::currentTimeMillis,
) : GoLearningProgressRepository {
    constructor(database: MocsDatabase, legacy: GoLearningLegacySource? = null) : this(RoomGoProgressStore(database), legacy)
    private val access = GoProgressAccess(store, GoProgressKind.LEARNING,
        { GoLearningProgressCodec.encode(legacy?.readCommitted() ?: GoLearningProgress()) }, clock)

    override suspend fun load() = checkNotNull(GoLearningProgressCodec.decode(access.load()))

    override suspend fun save(progress: GoLearningProgress) {
        val incoming = GoLearningProgressCodec.freeze(progress)
        access.update { current ->
            val previous = checkNotNull(GoLearningProgressCodec.decode(current))
            GoLearningProgressCodec.encode(GoLearningProgress(
                previous.completedLessonIds + incoming.completedLessonIds, incoming.lastLessonId,
            ))
        }
    }

    suspend fun clear() = access.clear()
}
