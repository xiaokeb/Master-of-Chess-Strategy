package com.masterofchessstrategy.games.tiger_goat

import androidx.room.withTransaction
import com.masterofchessstrategy.data.LoadTutorialProgressResult
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomTutorialProgressRepository
import com.masterofchessstrategy.data.TutorialProgress
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatStorageEpochGate

internal enum class TigerGoatTutorialStep(val title: String, val prompt: String) {
    REMOVE("开局移羊", "虎方从中央环选一只羊移走，随后由羊方摆棋。请选择一只可移的羊。"),
    PLACE("摆放待摆羊", "待摆区有 14 只羊。每个羊方回合摆一只到空点。请选择一个空点。"),
    MOVE("老虎沿线走棋", "老虎可沿连线走到相邻空点。先选一只可走的虎，再选相邻空点。"),
    QUIZ("复核连跳规则", "老虎完成一次合法跳吃后，仍有可以跳吃的方向。此时可以怎么做？"),
}

/** A practice board owns its own Native session and never writes an active match. */
internal class TigerGoatTutorialPractice(val step: TigerGoatTutorialStep) : AutoCloseable {
    private val rules = NativeTigerGoatRulesSession(NativeTigerGoatSession())
    private var selected: Int? = null
    val spec get() = rules.spec

    init {
        check(step != TigerGoatTutorialStep.QUIZ)
        if (step != TigerGoatTutorialStep.REMOVE) {
            check(rules.apply(TigerGoatAction(TigerGoatActionKind.REMOVE_GOAT, 6)) == 0)
        }
        if (step == TigerGoatTutorialStep.MOVE) {
            check(rules.apply(TigerGoatAction(TigerGoatActionKind.PLACE_GOAT, to = 0)) == 0)
        }
    }

    fun state(): TigerGoatDisplayState {
        val state = rules.snapshot()
        val shown = actions().mapNotNull { action -> when (step) {
            TigerGoatTutorialStep.REMOVE -> action.from
            TigerGoatTutorialStep.PLACE -> action.to
            TigerGoatTutorialStep.MOVE -> if (selected == null) action.from else action.to.takeIf { action.from == selected }
            TigerGoatTutorialStep.QUIZ -> null
        } }.toSet()
        return TigerGoatDisplayState(state.pieces, state.phase, state.toMove, state.reserve, state.captured,
            state.winner, selectedPoint = selected, legalDestinations = shown)
    }

    fun tap(point: Int): Boolean {
        val moves = actions()
        val action = moves.firstOrNull { move -> when (step) {
            TigerGoatTutorialStep.REMOVE -> move.from == point
            TigerGoatTutorialStep.PLACE -> move.to == point
            TigerGoatTutorialStep.MOVE -> move.from == selected && move.to == point
            TigerGoatTutorialStep.QUIZ -> false
        } }
        if (action != null) return rules.apply(action) == 0
        if (step == TigerGoatTutorialStep.MOVE && moves.any { it.from == point }) selected = point
        return false
    }

    private fun actions() = rules.legalActions().filter { action -> when (step) {
        TigerGoatTutorialStep.REMOVE -> action.kind == TigerGoatActionKind.REMOVE_GOAT
        TigerGoatTutorialStep.PLACE -> action.kind == TigerGoatActionKind.PLACE_GOAT
        TigerGoatTutorialStep.MOVE -> action.kind == TigerGoatActionKind.STEP
        TigerGoatTutorialStep.QUIZ -> false
    } }

    override fun close() = rules.close()
}

/** Only the tiger-goat row can qualify this game. A backup replaces it under the same gate. */
internal class TigerGoatTutorialProgress(private val database: MocsDatabase) {
    private val gate = TigerGoatStorageEpochGate.forDatabase(database)
    private val owner = gate.captureOwner()
    private val repository = RoomTutorialProgressRepository(database.tutorialProgressDao())
    val current get() = owner.epoch == gate.epoch.value

    suspend fun load(): Int = gate.access(owner) { read() }

    suspend fun complete(stepIndex: Int): Int = gate.access(owner) {
        database.withTransaction {
            val count = read()
            check(stepIndex in TigerGoatTutorialStep.entries.indices && stepIndex <= count)
            if (stepIndex < count) return@withTransaction count
            val next = count + 1
            repository.save(TutorialProgress(GameType.TIGER_AND_GOAT, TutorialProgress.CURRENT_CONTENT_VERSION,
                next, next == TigerGoatTutorialStep.entries.size, System.currentTimeMillis()))
            next
        }
    }

    private suspend fun read(): Int = when (val result = repository.load(GameType.TIGER_AND_GOAT)) {
        LoadTutorialProgressResult.NotFound -> 0
        LoadTutorialProgressResult.Incompatible -> error("虎羊棋教程记录无法读取")
        is LoadTutorialProgressResult.Loaded -> result.progress.completedStepCount
    }
}
