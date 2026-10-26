package com.masterofchessstrategy.engine

import com.masterofchessstrategy.engine.internal.NativeBindings

/** JNI owner for one fixed local seat; operations and close share a lock. */
class NativeLanzhouMahjongSession(
    seed: Long,
    dealerSeat: Int = 0,
    baseScore: Long = 1,
    override val viewerSeat: Int = 0,
) : LanzhouMahjongBotSession {
    init {
        require(seed >= 0 && dealerSeat in 0..3 && baseScore > 0 && viewerSeat in 0..3)
    }

    private val lock = Any()
    private var handle = NativeBindings.createLanzhouMahjong(
        seed, dealerSeat, baseScore, viewerSeat,
    ).also { check(it > 0) { "Native Lanzhou Mahjong session could not be created" } }

    override fun snapshot(): LanzhouMahjongPublicView = withHandle { nativeHandle ->
        val status = NativeBindings.lanzhouMahjongStatus(nativeHandle)
        check(status.size == 10) { "Malformed Mahjong status" }
        val phase = LanzhouMahjongPhase.entries.firstOrNull { it.code == status[0] }
            ?: error("Invalid Mahjong phase")
        check(status[1] in 0..3 && status[2] in 0..3 && status[3] in 0..108)
        check(status[4] in -1..26 && status[5] in -1..3)
        check(status[6] in -1..3 && status[7] in -1..3 && status[8] in 0..3)
        check(status[9] in 0..31)
        val flags = status[9]
        val outcome = LanzhouMahjongOutcomeView(
            optionalSeat(status[6]), optionalSeat(status[7]), status[8],
            flags and 1 != 0, flags and 2 != 0, flags and 4 != 0,
            flags and 8 != 0, flags and 16 != 0,
        )
        val seats = (0..3).map { seat ->
            LanzhouMahjongBridgeDecoder.seat(seat, viewerSeat,
                NativeBindings.lanzhouMahjongSeatView(nativeHandle, seat))
        }
        val scores = NativeBindings.lanzhouMahjongNetScores(nativeHandle)
        check(scores.size == 4) { "Malformed Mahjong settlement" }
        LanzhouMahjongPublicView(
            phase, status[1], status[2], status[3], optionalTile(status[4]),
            optionalSeat(status[5]), outcome, seats, scores.toList(),
        )
    }

    override fun legalActions(): List<LanzhouMahjongAction> = withHandle {
        val fields = NativeBindings.lanzhouMahjongLegalActions(it)
        check(fields.size % 2 == 0) { "Malformed Mahjong action list" }
        fields.toList().chunked(2).map { (kind, tile) ->
            val actionKind = LanzhouMahjongActionKind.entries.firstOrNull { value -> value.code == kind }
                ?: error("Invalid Mahjong action kind")
            LanzhouMahjongAction(actionKind, tile)
        }
    }

    override fun apply(action: LanzhouMahjongAction): ActionResult = withHandle {
        val code = NativeBindings.applyLanzhouMahjongAction(it, action.kind.code, action.tile)
        if (code == 0) ActionResult.Accepted else ActionResult.Rejected(decodeError(code))
    }

    override fun undo(): Boolean = withHandle(NativeBindings::undoLanzhouMahjong)
    override fun advanceOtherSeats(
        difficulty: Difficulty,
        choiceSeed: Long,
        stepLimit: Int,
    ): LanzhouMahjongBotAdvance = withHandle {
        require(choiceSeed >= 0 && stepLimit in 1..512)
        LanzhouMahjongBridgeDecoder.advance(
            NativeBindings.advanceLanzhouMahjongOtherSeats(it, difficulty.code, choiceSeed, stepLimit),
            stepLimit,
        )
    }
    override fun serialize(): ByteArray = withHandle(NativeBindings::serializeLanzhouMahjong).copyOf()
    override fun restore(data: ByteArray): RestoreResult = withHandle {
        val code = NativeBindings.restoreLanzhouMahjong(it, data.copyOf())
        if (code == 0) RestoreResult.Restored else RestoreResult.Rejected(decodeError(code))
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            NativeBindings.releaseLanzhouMahjong(handle)
            handle = 0L
        }
    }

    private fun optionalSeat(value: Int): Int? = value.takeIf { it >= 0 }
    private fun optionalTile(value: Int): Int? = value.takeIf { it >= 0 }

    private fun decodeError(code: Int): EngineError = when (code) {
        1 -> EngineError.ILLEGAL_ACTION
        2 -> EngineError.INVALID_STATE
        3 -> EngineError.CORRUPTED_DATA
        4 -> EngineError.UNSUPPORTED
        else -> error("Invalid Mahjong error code: $code")
    }

    private inline fun <T> withHandle(operation: (Long) -> T): T = synchronized(lock) {
        check(handle != 0L) { "Lanzhou Mahjong session is closed" }
        operation(handle)
    }
}
