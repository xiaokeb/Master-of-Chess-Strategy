package com.masterofchessstrategy.games.competition_mahjong.auto.storage

import android.os.Bundle
import android.os.SystemClock
import androidx.room.Room
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.GameBackupRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomLocalDataBackupRepository
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.CompetitionMahjongAutoPolicy
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoRunConfig
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoReplayInstrumentedTest.Companion.buildNativeArchive
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongCoordinatedBackupRepository
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongStorageEpochGate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@LargeTest
class CompetitionMahjongAutoVolumeInstrumentedTest {
    @Test fun mixedPolicyArchivesRoundTripWithoutChangingBytesOrGrowth() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val count = InstrumentationRegistry.getArguments().getString("mahjong_auto_volume_series", "100").toInt()
        require(count == 100 || count == 500)
        val metrics = Bundle().apply { putInt("volume_auto_series", count) }
        val name = "competition-auto-volume-${UUID.randomUUID()}.db"
        val viewer = 2
        val registry = GameBackupRegistry(listOf(CompetitionMahjongAutoBackupAdapter()))
        val pack = ChineseChessEndgamePackParser.loadBundled(context.assets)
        fun open() = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        fun backup(db: MocsDatabase) = CompetitionMahjongCoordinatedBackupRepository(
            RoomLocalDataBackupRepository(db, pack, { false }, registry),
            CompetitionMahjongStorageEpochGate.forDatabase(db),
        )
        suspend fun rows(db: MocsDatabase) = db.competitionMahjongAutoDao().let { dao ->
            dao.keys().associateWith { key -> checkNotNull(dao.find(key.viewerPlayerId, key.recordKey)) }
        }

        try {
            // Independent sources are reused with distinct UUIDs only for storage load.
            val sample = measured(metrics, "generate") {
                (0..3).map { index ->
                    buildNativeArchive(CompetitionMahjongAutoRunConfig(
                        UUID(0L, index.toLong()).toString(), 73L + index, viewer, listOf(0, 1, 2, 3), 109L + index,
                        if (index == 0) CompetitionMahjongAutoPolicy.BASELINE else CompetitionMahjongAutoPolicy.CALIBRATED,
                    )).checkpoint
                }
            }
            val (original, document) = withDatabase(open()) { db ->
                val store = RoomCompetitionMahjongAutoStore(db, viewer) { 100L }
                assertNull(store.load(viewer))
                measured(metrics, "populate") {
                    repeat(count) { index ->
                        val source = sample[index % sample.size]
                        val config = source.config.let {
                            CompetitionMahjongAutoRunConfig(
                                UUID(1L, index.toLong()).toString(), it.seed, viewer, it.playerBotLevels, it.decisionSeed, it.policy,
                            )
                        }
                        val checkpoint = CompetitionMahjongAutoCheckpoint(
                            config, source.settings, source.seriesIndex, source.activeHandIndex, source.completedHands,
                            source.baseline, source.engineRecord, source.openingRecord, source.elapsedMillis,
                        )
                        store.save(checkpoint, CompetitionMahjongAutoArchive(checkpoint))
                    }
                }
                val list = measured(metrics, "read_history") { store.list(viewer) }
                assertEquals(count, list.size)
                measured(metrics, "write_favorite") { store.setFavorite(list.last().key, viewer, true) }
                val stored = rows(db)
                assertEquals(count + 1, stored.size)
                val exported = measured(metrics, "export") { backup(db).export(200) }
                stored to exported
            }
            metrics.putInt("volume_auto_export_bytes", document.size)
            withDatabase(open()) { db ->
                val store = RoomCompetitionMahjongAutoStore(db, viewer)
                store.delete(store.list(viewer).last().key, viewer)
                assertEquals(count - 1, store.list(viewer).size)
                measured(metrics, "restore") { backup(db).restore(document) }
            }
            withDatabase(open()) { db ->
                val restored = rows(db)
                assertEquals(original.keys, restored.keys)
                original.forEach { (key, expected) ->
                    val actual = restored.getValue(key)
                    assertEquals(expected.savedAtEpochMillis, actual.savedAtEpochMillis)
                    assertEquals(expected.favorite, actual.favorite)
                    assertArrayEquals(expected.payload, actual.payload)
                }
                val store = RoomCompetitionMahjongAutoStore(db, viewer)
                val final = checkNotNull(store.load(viewer))
                assertEquals(CompetitionMahjongAutoPolicy.CALIBRATED, final.config.policy)
                assertEquals(16, final.ledger.completedHandCount)
                assertEquals(1, store.list(viewer).count { it.favorite })
                assertNotNull(store.load(CompetitionMahjongAutoArchive(final).key, viewer))
                assertTrue(db.matchOutcomeDao().listAll().isEmpty())
            }
            instrumentation.sendStatus(2, metrics)
        } finally {
            assertTrue(context.deleteDatabase(name))
        }
    }

    private inline fun <T> withDatabase(database: MocsDatabase, action: (MocsDatabase) -> T): T =
        try {
            action(database)
        } finally {
            database.close()
        }

    private suspend fun <T> measured(metrics: Bundle, stage: String, action: suspend () -> T): T {
        val started = SystemClock.elapsedRealtime()
        return action().also { metrics.putLong("volume_auto_${stage}_ms", SystemClock.elapsedRealtime() - started) }
    }
}
