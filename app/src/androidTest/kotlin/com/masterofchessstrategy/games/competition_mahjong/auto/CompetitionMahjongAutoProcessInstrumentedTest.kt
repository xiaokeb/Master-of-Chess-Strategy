package com.masterofchessstrategy.games.competition_mahjong.auto

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoReplayInstrumentedTest.Companion.buildNativeArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoReplayRecord
import com.masterofchessstrategy.games.competition_mahjong.auto.storage.RoomCompetitionMahjongAutoStore
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofCatalog
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofRequest
import com.masterofchessstrategy.games.competition_mahjong.learning.RoomCompetitionMahjongLearningProofStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitionMahjongAutoProcessInstrumentedTest {
    @Test fun seedBeforeHandFifteenEnds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val external = InstrumentationRegistry.getArguments().getString("mahjong_auto_process_token")
        val token = external ?: UUID.randomUUID().toString()
        require(UUID.fromString(token).toString() == token)
        val name = "competition-auto-process-$token.db"
        check(!context.getDatabasePath(name).exists())
        val database = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        try {
            val proof = RoomCompetitionMahjongLearningProofStore(database, database.competitionMahjongLearningProofDao())
            CompetitionMahjongLearningProofCatalog.chapterIds.forEachIndexed { index, chapter ->
                proof.confirm(CompetitionMahjongLearningProofRequest(UUID.randomUUID().toString(), chapter,
                    CompetitionMahjongLearningProofCatalog.cardIds(chapter), index.toLong()))
            }
            val config = CompetitionMahjongAutoRunConfig(token, 73, 0, List(4) { 0 }, 109)
            val records = buildNativeArchive(config).checkpoint.completedHands
            val handIndex = 14
            val hand = records[handIndex]
            val handConfig = config.handConfig(0, handIndex, false)
            val events = CompetitionMahjongAutoRecord.eventsAfter(hand.baseline, hand.terminal, handConfig)
            val beforeTerminal = CompetitionMahjongAutoReplayRecord.prefix(hand.baseline, hand.terminal, events - 1, handConfig)
            val checkpoint = CompetitionMahjongAutoCheckpoint(config,
                CompetitionMahjongAutoSettings(stepDelayMillis = 16, archiveAll = true),
                0, handIndex, records.take(handIndex), hand.baseline, beforeTerminal)
            val store = RoomCompetitionMahjongAutoStore(database, 0)
            store.load(0)
            store.save(checkpoint)
            assertEquals(events - 1, store.load(0)?.eventsAfterOpening)
            assertTrue(store.list(0).isEmpty())
        } finally {
            database.close()
            if (external == null) assertTrue(context.deleteDatabase(name))
        }
    }
}
