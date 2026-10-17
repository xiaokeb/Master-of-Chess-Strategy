package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.data.CompetitionMahjongBackupAdapter
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatBackupAdapter
import com.masterofchessstrategy.games.xiangqi.MocxRuleRevisionFixtures
import org.junit.Assert.*
import org.junit.Test

/** Shared registration integration owns mixed fixtures; single-game tests register only their game. */
class RegisteredGameBackupMixedCodecTest {
    @Test fun mixedTigerAndCompetitionBackupRetainsBothIndependentEnvelopes() {
        val tiger = TigerGoatBackupAdapter { error("Codec tests must not run Native admission") }
        val mahjong = CompetitionMahjongBackupAdapter { error("Codec tests must not run Native admission") }
        val registry = GameBackupRegistry(listOf(tiger, mahjong))
        for (viewer in 0..3) {
            val config = matchConfig(viewer)
            val checkpoint = CompetitionMahjongMatchCheckpoint(config, matchLedger(config, 4), 4,
                matchRecord(config.handConfig(4).viewerSeat))
            val competition = CompetitionMahjongMatchArchive(viewer, config.matchId, 8,
                CompetitionMahjongMatchCheckpointCodec.encode(checkpoint))
            val tigerArchive = TigerGoatActiveArchive("tiger", TigerGoatBackupCodecTest.opening(), 0, 7)
            val fixture = MocxRuleRevisionFixtures.all().first()
            val source = LocalDataSnapshot(20,
                gameExtensions = listOf(tiger.encodeArchive(tigerArchive), mahjong.encodeArchive(competition)),
                activeSessions = listOf(GameSessionSnapshot(GameType.CHINESE_CHESS,
                    StoredGameMode.LOCAL_TWO_PLAYER, null, fixture.engineState, 7,
                    sessionId = "xiangqi-mixed", acceptedMoveCount = 8)),
                settings = AppSettings.DEFAULT.copy(selectedAppearanceCode = 5,
                    highlightConditionsMask = 7, aiFirstEnabled = true, soundEnabled = false,
                    updatedAtEpochMillis = 9))
            val encoded = LocalDataBackupCodec.encode(source, registry)
            val restored = LocalDataBackupCodec.decode(encoded, registry)
            assertEquals(source.settings, restored.settings)
            assertEquals(listOf(competition), restored.gameExtensions.filter { it.kind == "COMPETITION_MATCH" }
                .map(mahjong::decodeRecord))
            assertEquals(listOf(tigerArchive), restored.gameExtensions.filter { it.kind == "TIGER_GOAT_ACTIVE" }
                .map(tiger::decodeRecord))
            assertEquals("xiangqi-mixed", restored.activeSessions.single().sessionId)
            assertArrayEquals(fixture.engineState, restored.activeSessions.single().engineState)
            assertArrayEquals(encoded, LocalDataBackupCodec.encode(restored, registry))
        }
    }
}
