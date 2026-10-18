package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Immutable, Native-validated opening endpoint. Call parsing/restoration on a worker.
 * Imported openings are training sources; they must never acquire standard ranked eligibility.
 */
class GoAiOpening private constructor(
    bytes: ByteArray,
    val boardSize: Int,
    val handicapStones: Int,
    val blackKomiQuarters: Int,
    val currentPlayer: GoPlayer,
    events: List<GoAction>,
) {
    private val initialBytes = bytes.copyOf()
    private val initialEvents = events.toList()
    val eventCount get() = initialEvents.size
    fun copyBytes() = initialBytes.copyOf()
    fun config(humanPlayer: GoPlayer? = GoPlayer.BLACK, difficulty: GoAiDifficulty = GoAiDifficulty.SIMPLE) =
        GoAiMatchConfig(boardSize, handicapStones, humanPlayer, difficulty)

    /** Own a separate candidate for a new match/restart; never mutate a viewer or active match. */
    fun createEngine(factory: (Int, Int, Int) -> GoRecordEngine = nativeFactory): GoRecordEngine {
        val candidate = factory(boardSize, blackKomiQuarters, handicapStones)
        var transferred = false
        try {
            check(candidate.restore(copyBytes()) == RestoreResult.Restored) { "指定开局恢复失败。" }
            checkContinuation(candidate)
            check(candidate.phase == GoPhase.PLAYING && candidate.gameResult() == GameResult.ONGOING &&
                candidate.eventHistory().size == eventCount && candidate.currentPlayer.value == currentPlayer.code)
            transferred = true
            return candidate
        } finally { if (!transferred) candidate.close() }
    }

    /** The caller must first restore current bytes through Native, not decode a forged event list. */
    fun checkContinuation(current: GoRecordEngine) {
        require(current.boardSize == boardSize && current.handicapStones == handicapStones &&
            current.blackKomiQuarters == blackKomiQuarters) { "对局配置与指定开局不一致。" }
        val history = current.eventHistory()
        require(history.size >= eventCount && history.take(eventCount) == initialEvents) { "对局历史不属于指定开局。" }
    }

    companion object {
        private const val MAX_BYTES = 1_000_000
        private val nativeFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) }

        fun fromSnapshot(bytes: ByteArray, factory: (Int, Int, Int) -> GoRecordEngine = nativeFactory): GoAiOpening {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
            val saved = bytes.copyOf()
            return parse(factory) { it.restore(saved.copyOf()) }
        }

        fun fromSgf(text: String, factory: (Int, Int, Int) -> GoRecordEngine = nativeFactory): GoAiOpening {
            require(text.isNotBlank() && text.length <= MAX_BYTES)
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
            require(encoded.remaining() in 1..MAX_BYTES)
            return parse(factory) { it.restoreSgf(text) }
        }

        private fun parse(factory: (Int, Int, Int) -> GoRecordEngine, restore: (GoRecordEngine) -> RestoreResult): GoAiOpening {
            for (size in listOf(9, 13, 19)) factory(size, 15, 0).use { candidate ->
                if (restore(candidate) != RestoreResult.Restored) return@use
                require(candidate.boardSize == size && candidate.phase == GoPhase.PLAYING &&
                    candidate.gameResult() == GameResult.ONGOING) { "指定开局必须停在可继续行棋的局面。" }
                val handicap = candidate.handicapStones
                require(handicap == 0 || handicap in 2..9)
                require(candidate.blackKomiQuarters == if (handicap == 0) 15 else 0) { "指定开局不符合本项目中国规则贴子。" }
                val events = candidate.eventHistory().toList()
                require(events.size <= 16_386)
                val player = GoPlayer.entries.firstOrNull { it.code == candidate.currentPlayer.value }
                    ?: throw IllegalArgumentException("指定开局行棋方无效。")
                val canonical = candidate.serialize()
                require(canonical.isNotEmpty() && canonical.size <= MAX_BYTES)
                return GoAiOpening(canonical, size, handicap, candidate.blackKomiQuarters, player, events)
            }
            throw IllegalArgumentException("指定开局数据无效或不受支持。")
        }
    }
}
