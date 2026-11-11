package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import org.junit.Assert.*
import org.junit.Test

class GoOpeningStudyNativeInstrumentedTest {
    @Test fun allAuthoredLinesReachTheirExactPlayableNativeEndpointAndSgfRoundTrip() {
        val plans = GoOpeningStudyLibrary.openAll()
        assertEquals(6, plans.size)
        for (plan in plans) {
            assertEquals(plan.line.steps.size, plan.opening.eventCount)
            plan.opening.createEngine().use { endpoint ->
                assertEquals(plan.line.steps.map { GoAction.Place(it.point) }, endpoint.eventHistory())
                assertEquals(GoPhase.PLAYING, endpoint.phase); assertEquals(GameResult.ONGOING, endpoint.gameResult())
                val fromSgf = GoAiOpening.fromSgf(endpoint.exportSgf())
                assertEquals(plan.opening.boardSize, fromSgf.boardSize)
                assertEquals(plan.opening.eventCount, fromSgf.eventCount)
                assertEquals(plan.opening.currentPlayer, fromSgf.currentPlayer)
                fromSgf.createEngine().use { restored -> assertEquals(endpoint.eventHistory(), restored.eventHistory()) }
            }
        }
    }
}
