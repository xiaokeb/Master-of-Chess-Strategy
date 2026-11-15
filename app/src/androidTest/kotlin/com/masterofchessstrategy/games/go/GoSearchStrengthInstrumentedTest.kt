package com.masterofchessstrategy.games.go

import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.NativeGoSearchRuntime
import com.masterofchessstrategy.engine.RestoreResult
import java.security.MessageDigest
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

@LargeTest
class GoSearchStrengthInstrumentedTest {
    private data class Match(
        val board: Int,
        val seed: Long,
        val lower: GoAiDifficulty,
        val higherColor: GoPlayer,
    ) {
        val higher: GoAiDifficulty get() = GoAiDifficulty.entries[lower.ordinal + 1]
    }

    @Test fun runOneFrozenColorSlot() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val cohort = arguments.getString("go_strength_cohort")
        val slotText = arguments.getString("go_strength_slot")
        assumeTrue("Explicit benchmark selection is required", cohort != null || slotText != null)
        val slot = requireNotNull(slotText).toInt()
        val match = match(requireNotNull(cohort), slot)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = GoAiAssetModelLoader(instrumentation.targetContext, { NativeGoSearchRuntime(it.absolutePath) }).load()
        try {
            NativeGoEngine(match.board).use { game ->
                opening(match).forEach { point -> check(game.apply(GoAction.Place(point)) == ActionResult.Accepted) }
                val openingHash = sha256(game.serialize())
                val identity = "$cohort,$slot,${match.board},${match.seed},${match.lower.name},${match.higherColor.name},$openingHash"
                report("go_strength_start" to identity)
                val maxPlies = 3 * match.board * match.board
                var reason = "ply_limit"
                var plies = 0
                var overTwoSeconds = 0
                var maxSearchUs = 0L
                var blackVisits = 0L
                var whiteVisits = 0L
                val started = SystemClock.elapsedRealtimeNanos()
                while (plies < maxPlies && game.phase == GoPhase.PLAYING) {
                    val color = GoPlayer.entries.single { it.code == game.currentPlayer.value }
                    val difficulty = if (color == match.higherColor) match.higher else match.lower
                    val budget = androidGoPilotBudgets.getValue(difficulty)
                    val request = GoAiSearchRequest(
                        GoAiPositionSnapshot(GoAiPositionStamp("$cohort-$slot", plies.toLong()), game.serialize()),
                        budget,
                        GoAiCancellation(),
                    )
                    val searchStart = SystemClock.elapsedRealtimeNanos()
                    val outcome = owner.search(request)
                    val micros = (SystemClock.elapsedRealtimeNanos() - searchStart) / 1_000
                    if (micros > 2_000_000) overTwoSeconds++
                    maxSearchUs = maxOf(maxSearchUs, micros)
                    val suggestion = when (outcome) {
                        is GoAiSearchOutcome.Move -> outcome.suggestion
                        GoAiSearchOutcome.BudgetExhausted -> { reason = "exhausted"; null }
                        GoAiSearchOutcome.Cancelled -> { reason = "cancelled"; null }
                        GoAiSearchOutcome.Busy -> { reason = "busy"; null }
                    } ?: break
                    if (suggestion.visits !in 1..budget.maxVisits ||
                        (suggestion.action !is GoAction.Place && suggestion.action != GoAction.Pass) ||
                        game.apply(suggestion.action) != ActionResult.Accepted) {
                        reason = "invalid_move"
                        break
                    }
                    if (color == GoPlayer.BLACK) blackVisits += suggestion.visits else whiteVisits += suggestion.visits
                    plies++
                    if (plies % 16 == 0) report("go_strength_progress" to "$cohort,$slot,$plies")
                }
                if (game.phase == GoPhase.SCORING) {
                    check(game.eventHistory().takeLast(2) == listOf(GoAction.Pass, GoAction.Pass))
                    val proposal = game.scoringState()
                    check(proposal.deadStones.isEmpty())
                    for (color in GoPlayer.entries) {
                        check(game.apply(GoAction.ConfirmScore(color, proposal.proposalRevision)) == ActionResult.Accepted)
                    }
                    reason = "double_pass"
                }
                val score = game.finalScore()
                val result = game.gameResult()
                check((score != null) == (reason == "double_pass"))
                if (score != null) check(result == score.result && result != GameResult.ONGOING)
                val record = game.serialize()
                val sgf = game.exportSgf()
                NativeGoEngine(match.board).use { restored ->
                    check(restored.restore(record) == RestoreResult.Restored)
                    check(restored.serialize().contentEquals(record) && restored.finalScore() == score)
                    check(restored.eventHistory() == game.eventHistory() && restored.exportSgf() == sgf)
                }
                val scoreForHigher = when (result) {
                    GameResult.FIRST_PLAYER_WIN -> if (match.higherColor == GoPlayer.BLACK) "1" else "0"
                    GameResult.SECOND_PLAYER_WIN -> if (match.higherColor == GoPlayer.WHITE) "1" else "0"
                    GameResult.DRAW -> "0.5"
                    GameResult.ONGOING -> "-"
                }
                val row = listOf(identity, result.name, scoreForHigher, reason, plies,
                    score?.blackAreaQuarters ?: "-", score?.whiteAreaQuarters ?: "-",
                    blackVisits, whiteVisits, maxSearchUs, overTwoSeconds,
                    (SystemClock.elapsedRealtimeNanos() - started) / 1_000, sha256(record)).joinToString(",")
                report(
                    "go_strength_result" to row,
                    "go_strength_record" to Base64.encodeToString(record, Base64.NO_WRAP),
                    "go_strength_sgf" to Base64.encodeToString(sgf.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                )
            }
        } finally {
            owner.closeAndJoin()
        }
    }

    private fun match(cohort: String, slot: Int): Match {
        val color = if (slot % 2 == 0) GoPlayer.BLACK else GoPlayer.WHITE
        return when (cohort) {
            "pilot" -> {
                require(slot in 0 until 6)
                val lower = slot / 2
                Match(9, 39101L + lower, GoAiDifficulty.entries[lower], color)
            }
            "holdout-1" -> {
                require(slot in 0 until 144)
                val boardIndex = slot / 16 % 3
                val seed = 7531701001L + slot / 2 % 24
                Match(listOf(9, 13, 19)[boardIndex], seed, GoAiDifficulty.entries[slot / 48], color)
            }
            else -> error("Unknown Go strength cohort: $cohort")
        }
    }

    private fun opening(match: Match): List<BoardPosition> {
        val axes = listOf(2, match.board / 2, match.board - 3)
        val points = axes.flatMap { y -> axes.map { x -> BoardPosition(x, y) } }.toMutableList()
        java.util.Collections.shuffle(points, Random(match.seed))
        return points.take(6)
    }

    private fun report(vararg entries: Pair<String, String>) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            entries.forEach { (key, value) -> putString(key, value) }
        })
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
