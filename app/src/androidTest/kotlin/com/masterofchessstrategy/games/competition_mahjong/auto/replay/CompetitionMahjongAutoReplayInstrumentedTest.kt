package com.masterofchessstrategy.games.competition_mahjong.auto.replay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.NativeCompetitionMahjongAutoSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoHandRecord
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoRunConfig
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoSettings
import com.masterofchessstrategy.games.competition_mahjong.auto.isAutoTerminal
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real JNI admission for every stored hand and selected opaque event prefixes. */
@RunWith(AndroidJUnit4::class)
class CompetitionMahjongAutoReplayInstrumentedTest {
    @Test fun completeNativeArchiveReplaysExactPrefixesAcrossHandBoundary() = runBlocking {
        val archive = nativeArchive()
        val controller = CompetitionMahjongAutoReplayController(archive)
        try {
            assertTrue(controller.open())
            val first = archive.checkpoint.completedHands.first()
            val config = archive.checkpoint.config.handConfig(0, 0, false)
            val firstCount = controller.state.value.eventCount
            assertTrue(firstCount > 2)

            assertTrue(controller.seek(0, 2))
            val expectedPrefix = CompetitionMahjongAutoReplayRecord.prefix(
                first.baseline, first.terminal, 2, config)
            NativeCompetitionMahjongAutoSession(config).use { independent ->
                assertEquals(RestoreResult.Restored, independent.restore(expectedPrefix))
                assertEquals(independent.snapshot(), controller.state.value.frame)
            }

            assertTrue(controller.seek(0, firstCount))
            assertTrue(controller.state.value.frame!!.isAutoTerminal())
            assertTrue(controller.forward(0, firstCount))
            assertEquals(1, controller.state.value.handIndex)
            assertEquals(0, controller.state.value.eventIndex)
            assertFalse(controller.state.value.frame!!.isAutoTerminal())
            assertTrue(controller.backward(1, 0))
            assertEquals(0, controller.state.value.handIndex)
            assertEquals(firstCount, controller.state.value.eventIndex)
        } finally {
            controller.close()
        }
    }

    @Test fun exportedEnvelopeRoundTripsBeforeNativeAdmissionOfFinalPosition() = runBlocking {
        val archive = nativeArchive()
        val exported = archive.export()
        val decoded = CompetitionMahjongAutoExportCodec.decode(exported.bytes, VIEWER)
        assertNotNull(decoded)
        assertTrue(archive.sameReplayContent(decoded!!))

        val controller = CompetitionMahjongAutoReplayController(decoded)
        try {
            assertTrue(controller.open())
            assertTrue(controller.selectHand(15))
            val lastCount = controller.state.value.eventCount
            assertTrue(controller.seek(15, lastCount))
            assertTrue(controller.state.value.atEnd)
            assertTrue(controller.state.value.frame!!.isAutoTerminal())
        } finally {
            controller.close()
        }
    }

    private fun CompetitionMahjongAutoArchive.sameReplayContent(other: CompetitionMahjongAutoArchive): Boolean {
        val left = checkpoint
        val right = other.checkpoint
        return left.config == right.config && left.ledger.results == right.ledger.results &&
            left.completedHands.zip(right.completedHands).all { (a, b) ->
                a.baseline.contentEquals(b.baseline) && a.terminal.contentEquals(b.terminal)
            }
    }

    companion object {
        private const val VIEWER = 2
        @Volatile private var cachedArchive: CompetitionMahjongAutoArchive? = null

        internal fun nativeArchive(): CompetitionMahjongAutoArchive = synchronized(this) {
            cachedArchive ?: buildNativeArchive(CompetitionMahjongAutoRunConfig(
                "84000000-0000-4000-8000-000000000001", 73, VIEWER, listOf(0, 1, 2, 3), 109,
            )).also { cachedArchive = it }
        }

        internal fun buildNativeArchive(config: CompetitionMahjongAutoRunConfig): CompetitionMahjongAutoArchive {
            val series = config.seriesConfig(0, false)
            val records = (0 until 16).map { hand ->
                val handConfig = config.handConfig(0, hand, false)
                NativeCompetitionMahjongAutoSession(handConfig).use { session ->
                    val baseline = session.serialize()
                    var accepted = 0
                    while (!session.snapshot().isAutoTerminal()) {
                        accepted += session.advance(32).acceptedActions
                        check(accepted in 1..4096) { "Native event budget exceeded at hand $hand" }
                    }
                    val terminal = session.serialize()
                    CompetitionMahjongAutoHandRecord(
                        CompetitionMahjongMatchLedger.captureTerminal(series, hand, session.snapshot()),
                        baseline,
                        terminal,
                    )
                }
            }
            val last = records.last()
            return CompetitionMahjongAutoArchive(
                CompetitionMahjongAutoCheckpoint(
                    config,
                    CompetitionMahjongAutoSettings(archiveAll = true),
                    0,
                    15,
                    records,
                    last.baseline,
                    last.terminal,
                    elapsedMillis = 123_000,
                ),
            )
        }
    }
}
