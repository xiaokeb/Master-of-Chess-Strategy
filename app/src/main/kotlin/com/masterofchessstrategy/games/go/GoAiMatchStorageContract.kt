package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GoPlayer

/** Storage contracts do not depend on the running search coordinator or match lifecycle. */
data class GoAiSearchBudget(val maxVisits: Int, val maxTimeMillis: Long) {
    init { require(maxVisits in 1..512 && maxTimeMillis in 1..20_000) }
}

/** Search budgets are initial settings, not a verified playing-strength classification. */
enum class GoAiDifficulty(val budget: GoAiSearchBudget, val undoLimit: Int) {
    SIMPLE(GoAiSearchBudget(1, 1_000), Int.MAX_VALUE),
    MEDIUM(GoAiSearchBudget(8, 3_000), 3),
    HARD(GoAiSearchBudget(32, 8_000), 1),
    MASTER(GoAiSearchBudget(128, 20_000), 0),
    ;
    val hintLimit get() = undoLimit
}

data class GoAiMatchConfig(
    val boardSize: Int = 19,
    val handicapStones: Int = 0,
    val humanPlayer: GoPlayer? = GoPlayer.BLACK, // null = AI versus AI.
    val difficulty: GoAiDifficulty = GoAiDifficulty.SIMPLE,
) {
    init { require(boardSize in setOf(9, 13, 19) && (handicapStones == 0 || handicapStones in 2..9)) }
    val automatic get() = humanPlayer == null
    fun isAi(player: GoPlayer) = humanPlayer != player
}

class GoAiSavedMatch(
    val matchId: String,
    val config: GoAiMatchConfig,
    bytes: ByteArray,
    val undoUsed: Int = 0,
    val hintUsed: Int = 0,
    val completionReserved: Boolean = false,
    val rememberedHuman: GoPlayer = config.humanPlayer ?: GoPlayer.BLACK,
    val automaticUsed: Boolean = config.automatic,
    openingBytes: ByteArray? = null,
    val autoContinueEnabled: Boolean = false,
    val autoContinueMaxGames: Int = 1,
    val autoContinueGameNumber: Int = 1,
) {
    private val saved = bytes.copyOf()
    private val opening = openingBytes?.copyOf()
    init { require(matchId.isNotBlank() && '\u0000' !in matchId && matchId.toByteArray(Charsets.UTF_8).size <= 128 &&
        saved.isNotEmpty() && saved.size <= 1_000_000 && undoUsed in 0..config.difficulty.undoLimit &&
        hintUsed in 0..config.difficulty.hintLimit && (!config.automatic || automaticUsed) &&
        (config.automatic || config.humanPlayer == rememberedHuman) &&
        (opening == null || opening.isNotEmpty() && opening.size <= 1_000_000) &&
        if (autoContinueEnabled) config.automatic && autoContinueMaxGames in 2..100 &&
            autoContinueGameNumber in 1..autoContinueMaxGames
        else autoContinueMaxGames == 1 && autoContinueGameNumber == 1) }
    fun copyBytes() = saved.copyOf()
    fun copyOpeningBytes() = opening?.copyOf()
    val hasSpecifiedOpening get() = opening != null
}

/** One active AI match checkpoint, not a completed-game or imported-record library. */
interface GoAiMatchRepository {
    suspend fun load(): GoAiSavedMatch?
    suspend fun save(match: GoAiSavedMatch)
}

/** Store a verified finished match. Same matchId and content must succeed without duplicate rewards.
 * Production implementation belongs to the shared Room completed-game transaction.
 */
fun interface GoAiCompletedMatchSink {
    suspend fun storeCompleted(match: GoAiSavedMatch)
}
