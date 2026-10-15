package com.masterofchessstrategy.engine.games.tiger_goat

/** Owns one opaque handle; calls and close are serialized for this session. */
class NativeTigerGoatSession(private val bridge: TigerGoatBridge = TigerGoatBindings) : TigerGoatSession {
    private val lock = Any()
    override val geometry = TigerGoatWire.decodeGeometry(bridge.geometry())
    private var handle = bridge.create().also { check(it > 0) { "Tiger-goat session creation failed" } }

    override fun snapshot() = withHandle { TigerGoatWire.decodeSnapshot(bridge.snapshot(it)) }
    override fun legalActions() = withHandle { TigerGoatWire.decodeActions(bridge.legalActions(it)) }
    override fun apply(action: TigerGoatAction): Int = withHandle {
        TigerGoatWire.validateAction(action)
        bridge.apply(it, action.kind.code, action.from, action.to).also { code ->
            check(code in 0..10) { "Invalid tiger-goat rule error" }
        }
    }
    override fun undo() = withHandle(bridge::undo)
    override fun reset() = withHandle(bridge::reset)
    override fun serialize() = withHandle { bridge.serialize(it).copyOf() }
    override fun restore(bytes: ByteArray) = withHandle {
        bytes.size in 53..196_658 && bridge.restore(it, bytes.copyOf())
    }
    override fun close() = synchronized(lock) {
        if (handle != 0L) {
            val released = handle
            handle = 0L
            bridge.release(released)
        }
    }
    private inline fun <T> withHandle(operation: (Long) -> T): T = synchronized(lock) {
        check(handle != 0L) { "Tiger-goat session is closed" }
        operation(handle)
    }
}

internal object TigerGoatWire {
    fun decodeGeometry(fields: IntArray): TigerGoatGeometry {
        check(fields.size == 229 && fields.take(3) == listOf(1, 37, 76)) {
            "Invalid tiger-goat geometry header"
        }
        val points = List(37) { TigerGoatPoint(fields[3 + 2 * it], fields[4 + 2 * it]) }
        val expected = List(25) { TigerGoatPoint(it % 5, it / 5) } + listOf(
            TigerGoatPoint(-1, 1), TigerGoatPoint(-1, 2), TigerGoatPoint(-1, 3),
            TigerGoatPoint(-2, 0), TigerGoatPoint(-2, 2), TigerGoatPoint(-2, 4),
            TigerGoatPoint(5, 1), TigerGoatPoint(5, 2), TigerGoatPoint(5, 3),
            TigerGoatPoint(6, 0), TigerGoatPoint(6, 2), TigerGoatPoint(6, 4),
        )
        check(points == expected) { "Invalid tiger-goat point coordinates" }
        val edges = List(76) { TigerGoatEdge(fields[77 + 2 * it], fields[78 + 2 * it]) }
        check(edges.all { it.from in 0..36 && it.to in 0..36 && it.from < it.to } &&
            edges.distinct().size == 76
        ) { "Invalid tiger-goat printed edges" }
        val expectedEdges = mutableSetOf<TigerGoatEdge>()
        fun connect(a: Int, b: Int) { expectedEdges += TigerGoatEdge(minOf(a, b), maxOf(a, b)) }
        for (y in 0..4) for (x in 0..4) {
            val point = y * 5 + x
            if (x < 4) connect(point, point + 1)
            if (y < 4) connect(point, point + 5)
            if (y < 4 && (x + y) % 2 == 0) {
                if (x < 4) connect(point, point + 6)
                if (x > 0) connect(point, point + 4)
            }
        }
        for ((apex, middle) in listOf(10 to 25, 14 to 31)) {
            for (row in 0..2) {
                connect(apex, middle + row)
                connect(middle + row, middle + 3 + row)
            }
            connect(middle, middle + 1); connect(middle + 1, middle + 2)
            connect(middle + 3, middle + 4); connect(middle + 4, middle + 5)
        }
        check(edges.toSet() == expectedEdges) { "Tiger-goat topology version mismatch" }
        return TigerGoatGeometry(points, edges)
    }

    fun decodeSnapshot(fields: IntArray): TigerGoatSnapshot {
        check(fields.size == 81) { "Invalid tiger-goat snapshot length" }
        val board = fields.take(37)
        check(board.all { it in 0..2 } && board.count { it == 1 } == 2) { "Invalid tiger-goat board" }
        check(fields[37] in 0..3 && fields[38] in 1..2 && fields[39] in 0..14 &&
            fields[40] in 0..22 && fields[41] in 0..2 && fields[43] in 0..65_535
        ) { "Invalid tiger-goat state fields" }
        check(board.count { it == 2 } + fields[39] + fields[40] == 22) { "Invalid tiger-goat counts" }
        check((44..80).all { fields[it] in 0..1 }) { "Invalid tiger-goat visited flags" }
        val visited = (0..36).filter { fields[44 + it] == 1 }.toSet()
        val phase = TigerGoatPhase.entries[fields[37]]
        val outcome = TigerGoatOutcome.entries[fields[41]]
        check((phase == TigerGoatPhase.FINISHED) == (outcome != TigerGoatOutcome.ONGOING)) {
            "Invalid tiger-goat terminal phase"
        }
        if (phase == TigerGoatPhase.CAPTURE_CHAIN) {
            check(fields[38] == 1 && fields[42] in 0..36 && board[fields[42]] == 1 &&
                fields[42] in visited && visited.size >= 2 && fields[43] > 0
            ) { "Invalid tiger-goat capture chain" }
        } else check(fields[42] == -1 && visited.isEmpty()) { "Unexpected tiger-goat capture chain" }
        if (phase == TigerGoatPhase.OPENING_REMOVAL) {
            val opening = List(37) {
                when (it) { 10, 14 -> 1; 6, 7, 8, 11, 13, 16, 17, 18 -> 2; else -> 0 }
            }
            check(board == opening && fields[38] == 1 && fields[39] == 14 &&
                fields[40] == 0 && fields[43] == 0
            ) { "Invalid tiger-goat opening" }
        } else if (phase != TigerGoatPhase.FINISHED) {
            check(fields[40] > 0) { "Tiger-goat opening removal was skipped" }
        }
        return TigerGoatSnapshot(board, phase, fields[38], fields[39], fields[40], outcome,
            fields[42], fields[43], visited)
    }

    fun decodeActions(fields: IntArray): List<TigerGoatAction> {
        check(fields.size % 3 == 0 && fields.size <= 37 * 37 * 3) { "Invalid tiger-goat action length" }
        val actions = List(fields.size / 3) {
            val offset = it * 3
            val kind = TigerGoatActionKind.entries.firstOrNull { kind -> kind.code == fields[offset] }
                ?: error("Invalid tiger-goat action kind")
            TigerGoatAction(kind, fields[offset + 1], fields[offset + 2]).also(::validateAction)
        }
        check(actions.distinct().size == actions.size) { "Duplicate tiger-goat action" }
        return actions
    }

    fun validateAction(action: TigerGoatAction) {
        check(when (action.kind) {
            TigerGoatActionKind.REMOVE_GOAT -> action.from in 0..36 && action.to == -1
            TigerGoatActionKind.PLACE_GOAT -> action.from == -1 && action.to in 0..36
            TigerGoatActionKind.STEP, TigerGoatActionKind.CAPTURE ->
                action.from in 0..36 && action.to in 0..36 && action.from != action.to
            TigerGoatActionKind.END_CHAIN, TigerGoatActionKind.RESIGN, TigerGoatActionKind.PASS_GOAT ->
                action.from == -1 && action.to == -1
        }) { "Invalid tiger-goat action point" }
    }
}
