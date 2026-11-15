package com.masterofchessstrategy.games.go

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.NativeGoSearchRuntime
import com.masterofchessstrategy.engine.RestoreResult
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@LargeTest
class GoProductionSearchLatencyInstrumentedTest {
    @Test fun measureProductionBudgetsAcrossThreeBoardSizes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loadStart = SystemClock.elapsedRealtimeNanos()
        val owner = GoAiAssetModelLoader(
            instrumentation.targetContext,
            runtimeFactory = { NativeGoSearchRuntime(it.absolutePath) },
        ).load()
        try {
            instrumentation.sendStatus(2, Bundle().apply {
                putLong("go_model_load_us", elapsedMicros(loadStart))
            })
            var sampleIndex = 0L
            for (size in listOf(9, 13, 19)) {
                for ((kind, setup) in listOf("empty" to emptyList(), "contact" to contactPosition(size))) {
                    val bytes = NativeGoEngine(size).use { engine ->
                        for (action in setup) assertEquals(ActionResult.Accepted, engine.apply(action))
                        assertEquals(GoPhase.PLAYING, engine.phase)
                        engine.serialize()
                    }
                    val positionHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    for (difficulty in GoAiDifficulty.entries) {
                        val budget = difficulty.budget
                        val request = GoAiSearchRequest(
                            GoAiPositionSnapshot(GoAiPositionStamp("latency-$size-$kind", sampleIndex), bytes),
                            budget,
                            GoAiCancellation(),
                        )
                        val started = SystemClock.elapsedRealtimeNanos()
                        val outcome = owner.search(request)
                        val micros = elapsedMicros(started)
                        val (status, visits, nativeMicros) = when (outcome) {
                            is GoAiSearchOutcome.Move -> {
                                val move = outcome.suggestion
                                assertTrue(move.visits in 1..budget.maxVisits)
                                assertTrue(move.elapsedMillis.isFinite() && move.elapsedMillis >= 0)
                                assertTrue(move.action is GoAction.Place || move.action == GoAction.Pass)
                                NativeGoEngine(size).use { verifier ->
                                    assertEquals(RestoreResult.Restored, verifier.restore(bytes))
                                    assertEquals(ActionResult.Accepted, verifier.apply(move.action))
                                }
                                Triple("legal", move.visits, (move.elapsedMillis * 1_000).toLong())
                            }
                            GoAiSearchOutcome.BudgetExhausted -> Triple("exhausted", 0, -1L)
                            else -> error("Unexpected result for $size/$kind/${difficulty.name}: $outcome")
                        }
                        val sample = listOf(sampleIndex, size, kind, positionHash, difficulty.name, budget.maxVisits,
                            budget.maxTimeMillis, status, visits, micros, nativeMicros, Debug.getPss()).joinToString(",")
                        instrumentation.sendStatus(2, Bundle().apply { putString("go_search_sample", sample) })
                        sampleIndex++
                    }
                }
            }
            assertEquals(24L, sampleIndex)
        } finally {
            owner.closeAndJoin()
        }
    }

    private fun elapsedMicros(started: Long) = (SystemClock.elapsedRealtimeNanos() - started) / 1_000

    private fun contactPosition(size: Int): List<GoAction.Place> {
        val center = size / 2
        val edge = size - 3
        return listOf(
            2 to 2, edge to edge, edge to 2, 2 to edge,
            center to center, center + 1 to center, center to center + 1, center + 1 to center + 1,
            center - 1 to center + 1, center - 1 to center, center to center - 1, center + 1 to center - 1,
            center - 1 to center - 1, center + 2 to center + 1, center to center + 2, center + 1 to center + 2,
            center - 2 to center, center + 2 to center,
        ).map { (x, y) -> GoAction.Place(BoardPosition(x, y)) }
    }
}
