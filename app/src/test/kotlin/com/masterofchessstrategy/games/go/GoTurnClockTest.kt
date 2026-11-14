package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoTurnClockTest {
    @Test fun chargesOnlyTheActiveSideAndKeepsBothBalancesAcrossTurns() {
        val initial = GoTurnClock.start(300_000, GoPlayer.BLACK, 1_000)
        val whiteTurn = initial.advance(16_000, GoPlayer.WHITE)
        assertEquals(285_000L, whiteTurn.remaining(GoPlayer.BLACK, 31_000))
        assertEquals(285_000L, whiteTurn.remaining(GoPlayer.WHITE, 31_000))
        val blackAgain = whiteTurn.advance(46_000, GoPlayer.BLACK)
        assertEquals(279_000L, blackAgain.remaining(GoPlayer.BLACK, 52_000))
        assertEquals(270_000L, blackAgain.remaining(GoPlayer.WHITE, 52_000))
    }

    @Test fun reviewPausesMainTimeAndResumeStartsTheDeclaredSide() {
        val whiteTurn = GoTurnClock.start(300_000, GoPlayer.WHITE, 1_000)
        val review = whiteTurn.advance(11_000, null)
        assertEquals(290_000L, review.remaining(GoPlayer.WHITE, 90_000))
        val blackTurn = review.advance(90_000, GoPlayer.BLACK)
        assertEquals(298_000L, blackTurn.remaining(GoPlayer.BLACK, 92_000))
        assertEquals(290_000L, blackTurn.remaining(GoPlayer.WHITE, 92_000))
    }

    @Test fun savedDeadlineExpiresAtZeroAndNeverProducesNegativeTime() {
        val saved = GoTurnClock.start(300_000, GoPlayer.BLACK, 10_000)
        assertNull(saved.expired(309_999))
        assertEquals(GoPlayer.BLACK, saved.expired(310_000))
        assertEquals(0L, saved.remaining(GoPlayer.BLACK, Long.MAX_VALUE))
        val stopped = saved.advance(310_000, null)
        assertEquals(0L, stopped.blackMillis)
        assertNull(stopped.expired(Long.MAX_VALUE))
    }
}
