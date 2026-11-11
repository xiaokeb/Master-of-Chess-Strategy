package com.masterofchessstrategy.engine

import com.masterofchessstrategy.engine.internal.CompetitionMahjongAutoBindings

/** Serialized lifecycle calls; close is idempotent and cannot race a JNI operation. */
class NativeCompetitionMahjongAutoSession(
    override val config: CompetitionMahjongAutoConfig,
) : CompetitionMahjongAutoSession {
    private val lock = Any()
    private var handle = CompetitionMahjongAutoBindings.create(
        config.seed, config.dealerSeat, config.roundWind, config.viewerSeat,
        config.botLevels.toIntArray(), config.decisionSeed,
    ).also { check(it > 0) { "Native automatic Mahjong session could not be created" } }

    override fun snapshot(): CompetitionMahjongPublicView = withHandle {
        CompetitionMahjongSnapshotDecoder.decode(CompetitionMahjongAutoBindings.snapshot(it), config.viewerSeat)
    }

    override fun advance(maxActions: Int): CompetitionMahjongAutoAdvance = withHandle {
        require(maxActions in 1..32)
        decodeCompetitionMahjongAutoAdvance(CompetitionMahjongAutoBindings.advance(it, maxActions), maxActions)
    }

    override fun undo(): Boolean = withHandle(CompetitionMahjongAutoBindings::undo)
    override fun serialize(): ByteArray = withHandle(CompetitionMahjongAutoBindings::serialize).copyOf()
    override fun restore(data: ByteArray): RestoreResult = withHandle {
        if (data.size in 28..1_048_576 && CompetitionMahjongAutoBindings.restore(it, data.copyOf())) RestoreResult.Restored
        else RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            CompetitionMahjongAutoBindings.release(handle)
            handle = 0L
        }
    }

    private inline fun <T> withHandle(operation: (Long) -> T): T = synchronized(lock) {
        check(handle != 0L) { "Automatic Mahjong session is closed" }
        operation(handle)
    }
}

internal fun decodeCompetitionMahjongAutoAdvance(fields: IntArray, budget: Int): CompetitionMahjongAutoAdvance {
    require(budget in 1..32)
    check(fields.size == 3 && fields[0] == 1)
    check(fields[1] in 0..budget && fields[2] in 0..1)
    check(fields[1] > 0 || fields[2] == 1)
    return CompetitionMahjongAutoAdvance(fields[1], fields[2] == 1)
}
