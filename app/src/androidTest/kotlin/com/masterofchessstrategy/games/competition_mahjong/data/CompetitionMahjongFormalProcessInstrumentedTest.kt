package com.masterofchessstrategy.games.competition_mahjong.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.createProductionGameBackupRepository
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongBotStrategy
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchController
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStore
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalHost
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomStorage
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitionMahjongFormalProcessInstrumentedTest {
    @Test fun seedMediumTable() = runBlocking {
        seedTable(CompetitionMahjongBotStrategy.POSITION_SEEDED_V1, wins = 10, level = 1)
    }

    @Test fun seedWmoMediumTable() = runBlocking {
        seedTable(CompetitionMahjongBotStrategy.POSITION_SEEDED_WMO, wins = 10, level = 1)
    }

    @Test fun seedWmoBetweenHands() = runBlocking {
        seedTable(CompetitionMahjongBotStrategy.POSITION_SEEDED_WMO, wins = 0, level = 0, settled = 15)
    }

    @Test fun seedWmoFinishedSeries() = runBlocking {
        seedTable(CompetitionMahjongBotStrategy.POSITION_SEEDED_WMO, wins = 0, level = 0, settled = 16)
    }

    @Test fun seedWmoKongsBetweenHands() = runBlocking {
        seedTable(CompetitionMahjongBotStrategy.POSITION_SEEDED_WMO_KONGS, wins = 0, level = 0, settled = 15)
    }

    @Test fun seedWmoKongsFinishedSeries() = runBlocking {
        seedTable(CompetitionMahjongBotStrategy.POSITION_SEEDED_WMO_KONGS, wins = 0, level = 0, settled = 16)
    }

    private suspend fun seedTable(strategy: CompetitionMahjongBotStrategy, wins: Int, level: Int, settled: Int = 0) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = InstrumentationRegistry.getArguments().getString("mahjong_process_token")
        val token = external ?: UUID.randomUUID().toString()
        require(UUID.fromString(token).toString() == token)
        val name = "competition-mahjong-process-qa-$token.db"
        check(!context.getDatabasePath(name).exists())
        val emptyLegacy = File(context.cacheDir, "competition-mahjong-process-qa-$token").apply { check(mkdir()) }
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = emptyLegacy
            override fun getFileStreamPath(name: String): File {
                require(name.matches(Regex("[a-z0-9-]+\\.bin")))
                return File(emptyLegacy, name)
            }
        }

        val db = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        try {
            val repository = createProductionGameBackupRepository(db, ChineseChessEndgamePackParser.loadBundled(context.assets),
                GoLocalStorage(db, isolated), FangqiLocalStorage.forDatabase(db)) { false }
            repository.restore(CompetitionMahjongNativeWinFixture.backup(wins, level, strategy))
            if (settled > 0) finishHands(db, settled)
            val stored = db.withTransaction {
                checkNotNull(CompetitionMahjongFormalRoomStorage.readMctxInTransaction(
                    db, db.competitionMahjongFormalRoomDao(), 0,
                ))
            }
            val snapshot = checkNotNull(CompetitionMahjongFormalSnapshotCodec.decode(stored, 0))
            val active = checkNotNull(snapshot.active)
            assertEquals(wins + if (settled == 16) 1 else 0, snapshot.archivedSeries.size)
            assertEquals(strategy, active.config.botStrategy)
            assertEquals(strategy.scoring.recordVersion, active.engineRecord[4].toInt())
            assertEquals(level, active.config.botLevel)
            assertEquals(settled, active.ledger.completedHandCount)
            assertEquals(if (level == 1) 3 else null, active.assistance?.hintRemaining)
            assertEquals(if (level == 1) 3 else null, active.assistance?.undoRemaining)
        } finally {
            db.close()
            check(emptyLegacy.listFiles()?.isEmpty() == true && emptyLegacy.delete())
            if (external == null) check(context.deleteDatabase(name))
        }
    }

    private suspend fun finishHands(db: MocsDatabase, count: Int) {
        val host = CompetitionMahjongFormalHost(db, 0)
        try {
            val session = (host.open() as CompetitionMahjongFormalHost.Entry.Ready).session
            val initial = checkNotNull(session.match.load(0))
            val usage = checkNotNull(initial.assistance)
            val store = object : CompetitionMahjongMatchStore {
                override suspend fun load(viewerPlayerId: Int) = session.match.load(viewerPlayerId)
                override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) =
                    session.match.save(checkpoint.withAssistance(usage))
            }
            val controller = CompetitionMahjongMatchController(initial.config, store)
            try {
                assertTrue(controller.open())
                repeat(count) { hand ->
                    finishCompetitionMahjongTestHand(controller, hand)
                    if (hand < count - 1) assertTrue(controller.nextHand(hand))
                }
            } finally {
                controller.close()
            }
        } finally {
            host.close()
        }
    }
}
