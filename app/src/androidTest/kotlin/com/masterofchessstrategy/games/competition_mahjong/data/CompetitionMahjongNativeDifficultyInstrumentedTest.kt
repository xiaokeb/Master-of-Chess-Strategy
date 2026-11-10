package com.masterofchessstrategy.games.competition_mahjong.data

import androidx.room.withTransaction
import androidx.test.filters.LargeTest
import com.masterofchessstrategy.data.LoadMatchStatisticsResult
import com.masterofchessstrategy.data.RoomMatchStatisticsRepository
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchController
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStore
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalHost
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomStorage
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import org.junit.Assert.*
import org.junit.Test

@LargeTest
class CompetitionMahjongNativeDifficultyInstrumentedTest {
    @Test fun restoredNativeWinsAdmitEachHigherTierAtItsExactThreshold() = withOwnedLanzhouDatabase { f ->
        for ((count, level) in listOf(10 to 1, 25 to 2, 45 to 3)) {
            f.repository().restore(CompetitionMahjongNativeWinFixture.backup(count, level))
            f.reopen()
            val host = CompetitionMahjongFormalHost(f.db, 0)
            try {
                val session = (host.open() as CompetitionMahjongFormalHost.Entry.Ready).session
                assertEquals((0..level).toList(), session.policy.allowedBotLevels)
                val before = checkNotNull(session.match.load(0))
                assertEquals(level, before.config.botLevel)
                val usage = session.policy.freshUsage(level)
                assertEquals(usage, before.assistance)
                val store = object : CompetitionMahjongMatchStore {
                    override suspend fun load(viewerPlayerId: Int) = session.match.load(viewerPlayerId)
                    override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) =
                        session.match.save(checkpoint.withAssistance(usage))
                }
                val controller = CompetitionMahjongMatchController(before.config, store)
                try {
                    assertTrue(controller.open())
                    val actions = controller.state.value.legalActions
                    val action = actions.first { it.kind == CompetitionMahjongRoundActionKind.DISCARD }
                    assertTrue(controller.apply(action, 0)?.accepted == true)
                    assertFalse(before.engineRecord.contentEquals(checkNotNull(session.match.load(0)).engineRecord))
                } finally {
                    controller.close()
                }
                val growth = (RoomMatchStatisticsRepository(f.db).load() as LoadMatchStatisticsResult.Loaded).statistics
                assertEquals(count, growth.byGame.getValue(GameType.STANDARD_MAHJONG).wins)
                assertEquals(count, growth.netStars)
                assertEquals(100 + (count - 10).coerceIn(0, 15) * 20 + (count - 25).coerceAtLeast(0) * 40, growth.score)
            } finally {
                host.close()
            }
        }
    }

    @Test fun nineNativeWinsCannotBorrowExistingMediumPermission() = withOwnedLanzhouDatabase { f ->
        f.repository().restore(CompetitionMahjongNativeWinFixture.backup(10, 1))
        suspend fun bytes() = f.db.withTransaction {
            checkNotNull(CompetitionMahjongFormalRoomStorage.readMctxInTransaction(f.db,
                f.db.competitionMahjongFormalRoomDao(), 0))
        }
        val original = bytes()
        val result = runCatching { f.repository().restore(CompetitionMahjongNativeWinFixture.backup(9, 1)) }
        assertTrue(result.exceptionOrNull().toString(), result.exceptionOrNull() is IllegalArgumentException)
        assertArrayEquals(original, bytes())
    }
}
