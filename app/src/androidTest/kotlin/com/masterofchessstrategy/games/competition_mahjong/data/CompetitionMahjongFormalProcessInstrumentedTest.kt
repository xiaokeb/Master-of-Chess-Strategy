package com.masterofchessstrategy.games.competition_mahjong.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.createProductionGameBackupRepository
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomStorage
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CompetitionMahjongFormalProcessInstrumentedTest {
    @Test fun seedMediumTable() = runBlocking {
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
            repository.restore(CompetitionMahjongNativeWinFixture.backup(wins = 10, activeLevel = 1))
            val stored = db.withTransaction {
                checkNotNull(CompetitionMahjongFormalRoomStorage.readMctxInTransaction(
                    db, db.competitionMahjongFormalRoomDao(), 0,
                ))
            }
            val snapshot = checkNotNull(CompetitionMahjongFormalSnapshotCodec.decode(stored, 0))
            val active = checkNotNull(snapshot.active)
            assertEquals(10, snapshot.archivedSeries.size)
            assertEquals(1, active.config.botLevel)
            assertEquals(3, active.assistance?.hintRemaining)
            assertEquals(3, active.assistance?.undoRemaining)
        } finally {
            db.close()
            check(emptyLegacy.listFiles()?.isEmpty() == true && emptyLegacy.delete())
            if (external == null) check(context.deleteDatabase(name))
        }
        Unit
    }
}
