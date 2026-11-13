package com.masterofchessstrategy.games.competition_mahjong.challenge

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.GameBackupRegistry
import com.masterofchessstrategy.data.LocalDataBackupCodec
import com.masterofchessstrategy.data.LocalDataSnapshot
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomLocalDataBackupRepository
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongStorageEpochGate
import com.masterofchessstrategy.progress.EndgameRewardTotals
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompetitionMahjongChallengeRoomInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper = MigrationTestHelper(instrumentation, MocsDatabase::class.java)

    @Test fun migrationCreatesEmptyChallengeSlotAndPreservesExistingRows() {
        val name = "competition-challenge-qa-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 24).use { db ->
                db.execSQL("INSERT INTO competition_mahjong_learning_proof (singletonId,proof) VALUES (0,X'0102')")
            }
            helper.runMigrationsAndValidate(name, 25, true, MocsDatabase.MIGRATION_24_25).use { db ->
                db.query("SELECT proof FROM competition_mahjong_learning_proof").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertArrayEquals(byteArrayOf(1, 2), rows.getBlob(0))
                }
                db.query("SELECT count(*) FROM competition_mahjong_challenge_progress").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(0, rows.getInt(0))
                }
            }
        } finally { assertTrue(instrumentation.targetContext.deleteDatabase(name)) }
    }

    @Test fun firstClearRoundTripsWithBackupAndStaleOwnerCannotWrite() = runBlocking {
        val context = instrumentation.targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        try {
            val gate = CompetitionMahjongStorageEpochGate.forDatabase(database)
            val writer = RoomCompetitionMahjongChallengeProgress(database, gate)
            val rewards = observeCompetitionChallengeRewards(database)
            val first = CompetitionMahjongChallengeLibrary.challenges.first().id
            assertEquals(EndgameRewardTotals(), rewards.first())
            repeat(2) { assertEquals(setOf(first), writer.complete(first).completedIds) }
            assertEquals(EndgameRewardTotals(1, 1, 10), rewards.first())
            val registry = GameBackupRegistry(listOf(CompetitionMahjongChallengeBackupAdapter()))
            val backup = RoomLocalDataBackupRepository(database, ChineseChessEndgamePackParser.loadBundled(context.assets),
                { false }, registry)
            val saved = gate.exclusive(false) { backup.export(101) }
            val empty = LocalDataBackupCodec.encode(LocalDataSnapshot(101), registry)
            gate.exclusive(true) { backup.restore(empty) }
            assertEquals(EndgameRewardTotals(), rewards.first())
            try { writer.complete(first); fail("Stale writer accepted") } catch (_: IOException) { }
            gate.exclusive(true) { backup.restore(saved) }
            assertEquals(EndgameRewardTotals(1, 1, 10), rewards.first())
            assertArrayEquals(saved, gate.exclusive(false) { backup.export(101) })
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
        } finally { database.close() }
    }
}
