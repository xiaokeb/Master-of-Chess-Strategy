package com.masterofchessstrategy.games.competition_mahjong.data

import android.os.Bundle
import android.os.SystemClock
import androidx.room.withTransaction
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.LoadMatchStatisticsResult
import com.masterofchessstrategy.data.RoomMatchStatisticsRepository
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalHost
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomStorage
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@LargeTest
class CompetitionMahjongFormalVolumeInstrumentedTest {
    @Test fun largeNativeArchiveCanBeUpdatedAndRestoredWithoutExtraGrowth() = withOwnedLanzhouDatabase { fixture ->
        val rawCount = InstrumentationRegistry.getArguments().getString("mahjong_volume_series") ?: "100"
        val count = rawCount.toInt()
        require(count in setOf(100, 500))
        val metrics = Bundle().apply { putInt("volume_series", count) }
        val backup = timed(metrics, "prepare_fixture") { CompetitionMahjongNativeWinFixture.volumeBackup(count) }
        timed(metrics, "native_admission_and_restore") { fixture.repository().restore(backup) }
        fixture.reopen()

        val host = CompetitionMahjongFormalHost(fixture.db, 0)
        val activeId = try {
            val ready = timed(metrics, "open_room_host") { host.open() } as CompetitionMahjongFormalHost.Entry.Ready
            val session = ready.session
            val initial = timed(metrics, "read_history") { session.history.load(0) }
            assertEquals(count, initial.entries.size)
            val targetId = initial.entries.last().matchId
            val changed = timed(metrics, "write_favorite") { session.history.setFavorite(0, targetId, true) }
            assertTrue(changed.entries.single { it.matchId == targetId }.favorite)
            val active = checkNotNull(session.match.load(0))
            assertEquals(3, active.config.botLevel)
            active.config.matchId
        } finally {
            host.close()
        }
        suspend fun persisted() = fixture.db.withTransaction {
            checkNotNull(CompetitionMahjongFormalRoomStorage.readMctxInTransaction(
                fixture.db, fixture.db.competitionMahjongFormalRoomDao(), 0,
            ))
        }
        val saved = persisted()
        val exported = timed(metrics, "export") { fixture.repository().export(2) }
        timed(metrics, "restore_export") { fixture.repository().restore(exported) }
        fixture.reopen()
        assertArrayEquals(saved, persisted())
        val snapshot = checkNotNull(CompetitionMahjongFormalSnapshotCodec.decode(saved, 0))
        assertEquals(activeId, snapshot.active?.config?.matchId)
        assertEquals(count, snapshot.statistics.seriesCount)
        assertEquals(count, snapshot.archivedSeries.size)
        assertEquals(1, snapshot.history.entries.count { it.favorite })
        val growth = (RoomMatchStatisticsRepository(fixture.db).load() as LoadMatchStatisticsResult.Loaded).statistics
        assertEquals(45, growth.byGame.getValue(GameType.STANDARD_MAHJONG).wins)
        metrics.putInt("volume_mctx_bytes", saved.size)
        metrics.putInt("volume_export_bytes", exported.size)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, metrics)
    }

    private suspend fun <T> timed(metrics: Bundle, stage: String, action: suspend () -> T): T {
        val started = SystemClock.elapsedRealtime()
        return action().also { metrics.putLong("volume_${stage}_ms", SystemClock.elapsedRealtime() - started) }
    }
}
