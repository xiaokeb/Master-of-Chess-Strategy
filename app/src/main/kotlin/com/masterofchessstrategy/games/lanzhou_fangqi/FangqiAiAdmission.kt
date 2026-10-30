package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningSnapshot
import java.security.MessageDigest
import java.util.Collections

/** Read failures are explicit facts, never an empty tutorial or an optimistic completion. */
internal sealed interface FangqiLearningRead {
    data class Ready(val progress: FangqiLearningSnapshot) : FangqiLearningRead
    data object Unavailable : FangqiLearningRead
    data object Incompatible : FangqiLearningRead
}

/** Structural snapshot only. The future production reader must verify the formal Fangqi ledger first. */
internal class FangqiQualifiedWinFacts private constructor(wins: Map<Difficulty, Int>) {
    val winsByDifficulty: Map<Difficulty, Int> = Collections.unmodifiableMap(wins.toMap())

    companion object {
        /** Not a provenance verifier or a reward receipt; never feed a generic statistics total here. */
        fun fromVerifiedLedgerCounts(gameType: GameType, wins: Map<Difficulty, Int>): FangqiQualifiedWinFacts {
            require(gameType == GameType.LANZHOU_SQUARE_CHESS) { "方棋资格只能采用本棋种正式胜场" }
            val owned = wins.toMap()
            require(owned.values.all { it >= 0 }) { "方棋有效胜场不能为负数" }
            return FangqiQualifiedWinFacts(Difficulty.entries.associateWith { owned[it] ?: 0 })
        }
    }
}

internal sealed interface FangqiQualifiedWinsRead {
    data class Verified(val facts: FangqiQualifiedWinFacts) : FangqiQualifiedWinsRead
    data object Unavailable : FangqiQualifiedWinsRead
    data object Incompatible : FangqiQualifiedWinsRead
}

internal enum class FangqiAiBlockReason {
    LEARNING_UNAVAILABLE, LEARNING_INCOMPATIBLE, TUTORIAL_INCOMPLETE,
    QUALIFIED_WINS_UNAVAILABLE, QUALIFIED_WINS_INCOMPATIBLE, INSUFFICIENT_WINS,
}

internal data class FangqiAiDifficultyCondition(
    val difficulty: Difficulty,
    val completedLessons: Int?,
    val totalLessons: Int,
    val precedingDifficulty: Difficulty?,
    val qualifyingWins: Int?,
    val requiredWins: Int,
    val blockedReason: FangqiAiBlockReason?,
) {
    val isUnlocked: Boolean get() = blockedReason == null
    val remainingWins: Int? get() = qualifyingWins?.let { (requiredWins - it).coerceAtLeast(0) }
}

internal sealed interface FangqiAiSelection {
    data object NotSelected : FangqiAiSelection
    data class InvalidCode(val requestedCode: Int) : FangqiAiSelection
    data class Locked(val condition: FangqiAiDifficultyCondition) : FangqiAiSelection
    data class Eligible(val difficulty: Difficulty) : FangqiAiSelection
}

/** A rule projection, not proof that Native, a ranked start or a transaction has been committed. */
internal class FangqiAiEligibility internal constructor(conditions: List<FangqiAiDifficultyCondition>) {
    val conditions: List<FangqiAiDifficultyCondition> = Collections.unmodifiableList(conditions.toList())

    init {
        require(this.conditions.map { it.difficulty } == Difficulty.entries)
    }

    fun condition(difficulty: Difficulty): FangqiAiDifficultyCondition = conditions.single { it.difficulty == difficulty }

    /** Preserve the explicit request, including a locked/invalid value; never upgrade or fall back silently. */
    fun select(requestedCode: Int?): FangqiAiSelection {
        if (requestedCode == null) return FangqiAiSelection.NotSelected
        val requested = Difficulty.entries.singleOrNull { it.code == requestedCode }
            ?: return FangqiAiSelection.InvalidCode(requestedCode)
        val condition = condition(requested)
        return if (condition.isUnlocked) FangqiAiSelection.Eligible(requested) else FangqiAiSelection.Locked(condition)
    }
}

/** Read-only identity for an already admitted save; it does not mint a new human/ranked game. */
internal data class FangqiAiExistingSession(
    val sessionId: String,
    val mode: FangqiStoredMode,
    val difficulty: Difficulty?,
    val updatedAtEpochMillis: Long,
    val acceptedActionCount: Int,
    val undoUseCount: Int,
    val hintUseCount: Int,
    val historySha256: String,
)

internal sealed interface FangqiAiNewGameDecision {
    data class Blocked(val selection: FangqiAiSelection) : FangqiAiNewGameDecision
    data class ExistingSessionConflict(val active: FangqiAiExistingSession, val requestedDifficulty: Difficulty) : FangqiAiNewGameDecision
    /** Intent only; Root must atomically re-read facts, resolve no-active and commit the real new game. */
    data class NewHumanGameIntent(val difficulty: Difficulty) : FangqiAiNewGameDecision {
        val gameType: GameType get() = GameType.LANZHOU_SQUARE_CHESS
        val mode: FangqiStoredMode get() = FangqiStoredMode.HUMAN_VS_AI
        val humanPlayerIndex: Int get() = 0
    }
}

internal sealed interface FangqiAiResumeDecision {
    data object NoActiveSession : FangqiAiResumeDecision
    data class ResumeAdmittedSession(val active: FangqiAiExistingSession) : FangqiAiResumeDecision
}

/** No storage, Native calls, source upgrades, navigation or reward effects. */
internal object FangqiAiAdmission {
    fun eligibility(learning: FangqiLearningRead, wins: FangqiQualifiedWinsRead): FangqiAiEligibility {
        // The Store owns persistence. Revalidate the strict versioned rows before deriving any unlock.
        val progress = (learning as? FangqiLearningRead.Ready)?.let { FangqiLearningSnapshot(it.progress.rows) }
        val tutorialFailure = when (learning) {
            FangqiLearningRead.Unavailable -> FangqiAiBlockReason.LEARNING_UNAVAILABLE
            FangqiLearningRead.Incompatible -> FangqiAiBlockReason.LEARNING_INCOMPATIBLE
            is FangqiLearningRead.Ready -> if (progress!!.tutorialComplete) null else FangqiAiBlockReason.TUTORIAL_INCOMPLETE
        }
        return FangqiAiEligibility(Difficulty.entries.map { difficulty ->
            val (preceding, required) = when (difficulty) {
                Difficulty.EASY -> null to 0
                Difficulty.MEDIUM -> Difficulty.EASY to 10
                Difficulty.HARD -> Difficulty.MEDIUM to 15
                Difficulty.MASTER -> Difficulty.HARD to 20
            }
            val count = if (preceding == null) 0 else (wins as? FangqiQualifiedWinsRead.Verified)
                ?.facts?.winsByDifficulty?.getValue(preceding)
            val blocked = tutorialFailure ?: if (preceding == null) null else when (wins) {
                FangqiQualifiedWinsRead.Unavailable -> FangqiAiBlockReason.QUALIFIED_WINS_UNAVAILABLE
                FangqiQualifiedWinsRead.Incompatible -> FangqiAiBlockReason.QUALIFIED_WINS_INCOMPATIBLE
                is FangqiQualifiedWinsRead.Verified -> if (checkNotNull(count) < required) FangqiAiBlockReason.INSUFFICIENT_WINS else null
            }
            FangqiAiDifficultyCondition(difficulty, progress?.completedLessons?.size,
                LanzhouFangqiLearningContent.lessons.size, preceding, count, required, blocked)
        })
    }

    fun newHumanGame(requestedCode: Int?, eligibility: FangqiAiEligibility,
        admittedActive: FangqiStoredSession? = null,
    ): FangqiAiNewGameDecision {
        val selection = eligibility.select(requestedCode)
        if (selection !is FangqiAiSelection.Eligible) return FangqiAiNewGameDecision.Blocked(selection)
        return if (admittedActive == null) FangqiAiNewGameDecision.NewHumanGameIntent(selection.difficulty)
            else FangqiAiNewGameDecision.ExistingSessionConflict(identity(admittedActive), selection.difficulty)
    }

    /** Resume preserves every stored mode/difficulty; it neither creates new qualification nor replaces a save. */
    fun resumeExisting(admittedActive: FangqiStoredSession?): FangqiAiResumeDecision =
        admittedActive?.let { FangqiAiResumeDecision.ResumeAdmittedSession(identity(it)) }
            ?: FangqiAiResumeDecision.NoActiveSession

    private fun identity(saved: FangqiStoredSession): FangqiAiExistingSession {
        val sha = MessageDigest.getInstance("SHA-256").digest(saved.engineState)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return FangqiAiExistingSession(saved.sessionId, saved.mode, saved.difficulty, saved.updatedAtEpochMillis,
            saved.acceptedActionCount, saved.undoUseCount, saved.hintUseCount, sha)
    }
}
