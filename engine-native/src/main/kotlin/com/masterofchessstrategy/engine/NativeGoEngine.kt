package com.masterofchessstrategy.engine

import com.masterofchessstrategy.engine.internal.NativeBindings

/** Owns one native Go session; all operations and close share the same lock. */
class NativeGoEngine(
    override val boardSize: Int = 19,
    initialBlackKomiQuarters: Int = 15,
    initialHandicapStones: Int = 0,
) : GoRecordEngine {
    init {
        require(boardSize == 9 || boardSize == 13 || boardSize == 19)
        require(initialBlackKomiQuarters == 0 || initialBlackKomiQuarters == 15)
        require(initialHandicapStones == 0 || initialHandicapStones in 2..9)
        require(initialHandicapStones == 0 || initialBlackKomiQuarters == 0)
    }

    private val lock = Any()
    private var handle = NativeBindings.createGoEngine(
        boardSize, initialBlackKomiQuarters, initialHandicapStones).also {
        check(it > 0) { "Native Go engine could not be created" }
    }

    override val gameType = GameType.GO

    override val blackKomiQuarters: Int
        get() = withHandle(NativeBindings::goKomiQuarters)

    override val handicapStones: Int
        get() = withHandle(NativeBindings::goHandicapStones)

    override val currentPlayer: PlayerId
        get() = PlayerId(withHandle(NativeBindings::goCurrentPlayer).also {
            check(it in 0..1) { "Native Go returned an invalid player" }
        })

    override val phase: GoPhase
        get() = scoringState().phase

    override fun reset() = withHandle(NativeBindings::resetGo)

    override fun apply(action: GoAction): ActionResult {
        val fields = encodeAction(action)
        val code = withHandle {
            NativeBindings.applyGoAction(it, fields[0], fields[1], fields[2], fields[3], fields[4])
        }
        return if (code == 0) ActionResult.Accepted else ActionResult.Rejected(engineError(code))
    }

    override fun undo(): Boolean = withHandle(NativeBindings::undoGo)

    override fun legalActions(): List<GoAction> {
        val fields = withHandle(NativeBindings::goLegalActions)
        check(fields.size % 5 == 0) { "Native Go returned malformed actions" }
        return fields.toList().chunked(5).map(::decodeAction)
    }

    override fun gameResult(): GameResult = decodeResult(withHandle(NativeBindings::goGameResult))

    override fun serialize(): ByteArray = withHandle(NativeBindings::serializeGo).copyOf()

    override fun restore(data: ByteArray): RestoreResult {
        val code = withHandle { NativeBindings.restoreGo(it, data.copyOf()) }
        return if (code == 0) RestoreResult.Restored else RestoreResult.Rejected(engineError(code))
    }

    override fun exportSgf(): String = withHandle {
        GoSgfTextCodec.decode(NativeBindings.exportGoSgf(it))
    }

    override fun restoreSgf(text: String): RestoreResult = withHandle {
        val data = GoSgfTextCodec.encodeOrNull(text)
            ?: return@withHandle RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
        val code = NativeBindings.restoreGoSgf(it, data)
        if (code == 0) RestoreResult.Restored else RestoreResult.Rejected(engineError(code))
    }

    override fun eventHistory(): List<GoAction> = withHandle {
        GoEventHistoryDecoder.decode(NativeBindings.goEventHistory(it), boardSize)
    }

    override fun stoneAt(point: BoardPosition): GoStone {
        requireInside(point)
        val code = withHandle { handle -> NativeBindings.goStoneAt(handle, point.x, point.y) }
        return GoStone.entries.firstOrNull { it.code == code }
            ?: error("Native Go returned an invalid stone")
    }

    override fun scoringState(): GoScoringState {
        val fields = withHandle(NativeBindings::goScoringState)
        check(fields.size >= 5 && fields[4] >= 0 && fields.size == 5 + 2 * fields[4]) {
            "Native Go returned malformed scoring state"
        }
        check(fields[1] >= 0 && fields[2] in 0..1 && fields[3] in 0..1)
        return GoScoringState(
            phase = decodePhase(fields[0]),
            proposalRevision = fields[1],
            blackConfirmed = fields[2] == 1,
            whiteConfirmed = fields[3] == 1,
            deadStones = (0 until fields[4]).map { index ->
                BoardPosition(fields[5 + 2 * index], fields[6 + 2 * index]).also(::requireInside)
            },
        )
    }

    override fun previewScore(): GoAreaScore? = decodeScore(withHandle(NativeBindings::goPreviewScore))

    override fun finalScore(): GoAreaScore? = decodeScore(withHandle(NativeBindings::goFinalScore))

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                NativeBindings.releaseGoEngine(handle)
                handle = 0L
            }
        }
    }

    private fun encodeAction(action: GoAction): IntArray = when (action) {
        is GoAction.Place -> {
            requireInside(action.point)
            intArrayOf(1, action.point.x, action.point.y, 0, 0)
        }
        GoAction.Pass -> intArrayOf(2, 0, 0, 0, 0)
        is GoAction.ToggleDeadGroup -> {
            requireInside(action.point)
            intArrayOf(3, action.point.x, action.point.y, action.actor.code, 0)
        }
        is GoAction.ConfirmScore -> {
            require(action.proposalRevision >= 0)
            intArrayOf(4, action.actor.code, action.proposalRevision, 0, 0)
        }
        is GoAction.ResumePlay -> intArrayOf(5, action.firstPlayer.code, 0, 0, 0)
    }

    private fun decodeAction(fields: List<Int>): GoAction = when (fields[0]) {
        1 -> GoAction.Place(BoardPosition(fields[1], fields[2]).also(::requireInside))
        2 -> GoAction.Pass
        3 -> GoAction.ToggleDeadGroup(
            BoardPosition(fields[1], fields[2]).also(::requireInside), decodePlayer(fields[3]))
        4 -> GoAction.ConfirmScore(decodePlayer(fields[1]), fields[2])
        5 -> GoAction.ResumePlay(decodePlayer(fields[1]))
        else -> error("Native Go returned an unknown action")
    }

    private fun decodeScore(fields: IntArray): GoAreaScore? {
        if (fields.isEmpty()) return null
        check(fields.size == 5) { "Native Go returned malformed score" }
        return GoAreaScore(fields[0], fields[1], fields[2], decodeResult(fields[3]), fields[4])
    }

    private fun decodePlayer(code: Int): GoPlayer =
        GoPlayer.entries.firstOrNull { it.code == code }
            ?: error("Native Go returned an invalid player")

    private fun decodePhase(code: Int): GoPhase =
        GoPhase.entries.firstOrNull { it.code == code }
            ?: error("Native Go returned an invalid phase")

    private fun decodeResult(code: Int): GameResult =
        GameResult.entries.getOrNull(code) ?: error("Native Go returned an invalid result")

    private fun engineError(code: Int): EngineError = when (code) {
        1 -> EngineError.ILLEGAL_ACTION
        2 -> EngineError.INVALID_STATE
        3 -> EngineError.CORRUPTED_DATA
        4 -> EngineError.UNSUPPORTED
        else -> error("Native Go returned an invalid error code: $code")
    }

    private fun requireInside(point: BoardPosition) {
        require(point.x < boardSize && point.y < boardSize) { "Point is outside the Go board" }
    }

    private inline fun <T> withHandle(operation: (Long) -> T): T = synchronized(lock) {
        check(handle != 0L) { "Go session is closed" }
        operation(handle)
    }
}
