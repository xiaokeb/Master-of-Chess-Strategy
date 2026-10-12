package com.masterofchessstrategy.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseChessSoundPlaybackTest {
    @Test fun firstCueWaitsForItsSampleAndLoadsOnlyOnce() {
        val fixture = Fixture()
        assertFalse(fixture.playback.play(ChineseChessSoundCue.VICTORY))
        fixture.playback.onLoadCompleted(ChineseChessSoundCue.MOVE, true)
        assertTrue(fixture.played.isEmpty())
        fixture.playback.onLoadCompleted(ChineseChessSoundCue.VICTORY, true)
        fixture.playback.onLoadCompleted(ChineseChessSoundCue.VICTORY, true)
        assertEquals(listOf(ChineseChessSoundCue.VICTORY), fixture.played)
    }

    @Test fun latestCueReplacesPendingAndLoadedCueClearsOlderPending() {
        val fixture = Fixture()
        fixture.playback.play(ChineseChessSoundCue.MOVE)
        fixture.playback.play(ChineseChessSoundCue.CAPTURE)
        fixture.playback.onLoadCompleted(ChineseChessSoundCue.MOVE, true)
        fixture.playback.onLoadCompleted(ChineseChessSoundCue.CAPTURE, true)
        assertEquals(listOf(ChineseChessSoundCue.CAPTURE), fixture.played)
        fixture.playback.play(ChineseChessSoundCue.DRAW)
        fixture.playback.play(ChineseChessSoundCue.MOVE)
        fixture.playback.onLoadCompleted(ChineseChessSoundCue.DRAW, true)
        assertEquals(listOf(ChineseChessSoundCue.CAPTURE, ChineseChessSoundCue.MOVE), fixture.played)
    }

    @Test fun disablingStopsPlaybackAndDiscardsPendingUntilANewEvent() {
        val fixture = Fixture()
        fixture.playback.play(ChineseChessSoundCue.VICTORY)
        fixture.playback.setEnabled(false)
        fixture.playback.setEnabled(false)
        fixture.playback.onLoadCompleted(ChineseChessSoundCue.VICTORY, true)
        assertFalse(fixture.playback.play(ChineseChessSoundCue.VICTORY))
        fixture.playback.setEnabled(true)
        assertTrue(fixture.played.isEmpty())
        assertEquals(1, fixture.stops)
        assertTrue(fixture.playback.play(ChineseChessSoundCue.VICTORY))
        assertEquals(listOf(ChineseChessSoundCue.VICTORY), fixture.played)
    }

    @Test fun disablingAnActiveCueStopsWithoutResumeOrHistoricalPlayback() {
        val fixture = Fixture()
        fixture.playback.onLoadCompleted(ChineseChessSoundCue.MOVE, true)
        fixture.playback.play(ChineseChessSoundCue.MOVE)
        fixture.playback.setEnabled(false)
        fixture.playback.setEnabled(true)
        assertEquals(1, fixture.stops)
        assertEquals(listOf(ChineseChessSoundCue.MOVE), fixture.played)
    }

    @Test fun failedDecodeAndRejectedStreamDoNotReportPlaybackSuccess() {
        val fixture = Fixture(accept = false)
        fixture.playback.onLoadCompleted(ChineseChessSoundCue.DEFEAT, false)
        assertFalse(fixture.playback.isLoaded(ChineseChessSoundCue.DEFEAT))
        assertFalse(fixture.playback.play(ChineseChessSoundCue.DEFEAT))
        fixture.playback.onLoadCompleted(ChineseChessSoundCue.MOVE, true)
        assertFalse(fixture.playback.play(ChineseChessSoundCue.MOVE))
    }

    @Test fun closeDiscardsPendingRejectsPlaybackAndReleasesExactlyOnce() {
        val fixture = Fixture()
        fixture.playback.play(ChineseChessSoundCue.DRAW)
        fixture.playback.close()
        fixture.playback.close()
        fixture.playback.setEnabled(true)
        fixture.playback.onLoadCompleted(ChineseChessSoundCue.DRAW, true)
        assertFalse(fixture.playback.isLoaded(ChineseChessSoundCue.DRAW))
        assertFalse(fixture.playback.play(ChineseChessSoundCue.DRAW))
        assertTrue(fixture.played.isEmpty())
        assertEquals(1, fixture.releases)
    }

    private class Fixture(accept: Boolean = true) {
        val played = mutableListOf<ChineseChessSoundCue>()
        var stops = 0
        var releases = 0
        val playback = ChineseChessSoundPlayback(
            playLoaded = { played += it; accept },
            stopPlayback = { stops += 1 },
            release = { releases += 1 },
        )
    }
}
