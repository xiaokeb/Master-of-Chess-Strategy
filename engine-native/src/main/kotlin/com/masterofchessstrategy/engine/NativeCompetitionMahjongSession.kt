package com.masterofchessstrategy.engine

import com.masterofchessstrategy.engine.internal.NativeBindings

/** JNI owner with one immutable local seat; no opponent-hand query is available. */
class NativeCompetitionMahjongSession(
    seed: Long,
    dealerSeat: Int = 0,
    roundWind: Int = 0,
    override val viewerSeat: Int = 0,
) : CompetitionMahjongRuleSession {
    init { require(seed >= 0 && dealerSeat in 0..3 && roundWind in 0..3 && viewerSeat in 0..3) }
    private val lock = Any()
    private var handle = NativeBindings.createCompetitionMahjong(seed, dealerSeat, roundWind, viewerSeat)
        .also { check(it > 0) { "Native competition Mahjong session could not be created" } }

    override fun snapshot(): CompetitionMahjongPublicView = withHandle {
        CompetitionMahjongSnapshotDecoder.decode(NativeBindings.competitionMahjongSnapshot(it), viewerSeat)
    }

    override fun legalActions(): List<CompetitionMahjongAction> = withHandle {
        val fields = NativeBindings.competitionMahjongLegalActions(it)
        check(fields.size <= 4096 && fields.size % 3 == 0)
        fields.toList().chunked(3).map { (kind, tile, sequence) ->
            val value = CompetitionMahjongRoundActionKind.entries.firstOrNull { it.code == kind }
                ?: error("Invalid Mahjong action kind")
            CompetitionMahjongAction(value, tile, sequence)
        }
    }

    override fun apply(action: CompetitionMahjongAction): CompetitionMahjongApplyResult = withHandle {
        val result = NativeBindings.applyCompetitionMahjongAction(it, action.kind.code, action.tile, action.sequenceStart)
        check(result.size == 3 && result[0] in 0..1 && result[1] in 0..6 && result[2] in 0..1)
        check((result[0] == 1) == (result[1] == 0) && (result[2] == 0 || result[0] == 1))
        CompetitionMahjongApplyResult(result[0] == 1, CompetitionMahjongRoundError.entries[result[1]], result[2] == 1)
    }

    override fun advanceBots(level: Int, seed: Long, maxActions: Int): Int = withHandle {
        require(level in 0..3 && seed >= 0 && maxActions in 1..256)
        NativeBindings.advanceCompetitionMahjongBots(it, level, seed, maxActions)
            .also { count -> check(count in 0..maxActions) }
    }

    override fun undo(): Boolean = withHandle(NativeBindings::undoCompetitionMahjong)
    override fun serialize(): ByteArray = withHandle(NativeBindings::serializeCompetitionMahjong).copyOf()
    override fun restore(data: ByteArray): RestoreResult = withHandle {
        if (data.size in 16..1_048_576 && NativeBindings.restoreCompetitionMahjong(it, data.copyOf())) RestoreResult.Restored
        else RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            NativeBindings.releaseCompetitionMahjong(handle)
            handle = 0L
        }
    }

    private inline fun <T> withHandle(operation: (Long) -> T): T = synchronized(lock) {
        check(handle != 0L) { "Competition Mahjong session is closed" }
        operation(handle)
    }
}
