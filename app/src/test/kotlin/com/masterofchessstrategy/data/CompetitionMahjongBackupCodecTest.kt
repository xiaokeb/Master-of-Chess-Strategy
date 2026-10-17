package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.data.CompetitionMahjongBackupAdapter
import com.masterofchessstrategy.games.competition_mahjong.match.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class CompetitionMahjongBackupCodecTest {
    private val adapter = CompetitionMahjongBackupAdapter()
    private val registry = GameBackupRegistry(listOf(adapter))
    @Test fun allFourStablePlayersAndEveryRoundSurviveCanonicalV4Backup() {
        for (viewer in 0..3) for (index in listOf(0, 4, 8, 12)) {
            val config = matchConfig(viewer)
            val checkpoint = CompetitionMahjongMatchCheckpoint(config, matchLedger(config, index), index,
                matchRecord(config.handConfig(index).viewerSeat))
            val archive = CompetitionMahjongMatchArchive(viewer, config.matchId, 8,
                CompetitionMahjongMatchCheckpointCodec.encode(checkpoint))
            val source = LocalDataSnapshot(9, gameExtensions = listOf(adapter.encodeArchive(archive)),
                tutorialProgress = listOf(TutorialProgress(GameType.STANDARD_MAHJONG, 1, 4, true, 7)))
            val bytes = LocalDataBackupCodec.encode(source, registry)
            assertTrue(bytes.toString(Charsets.UTF_8).startsWith("MOCS-BACKUP|4\n"))
            assertEquals(source, LocalDataBackupCodec.decode(bytes, registry))
            assertEquals(archive, adapter.decodeRecord(LocalDataBackupCodec.decode(bytes, registry).gameExtensions.single()))
            assertArrayEquals(bytes, LocalDataBackupCodec.encode(LocalDataBackupCodec.decode(bytes, registry), registry))
        }
    }

    @Test fun oldOneTwoAndThreeBackupsRemainReadableWithoutInventingCompetitionRows() {
        for (version in 1..3) {
            val restored = LocalDataBackupCodec.decode(signed("MOCS-BACKUP|$version\nCREATED|9\n"), registry)
            assertTrue(restored.gameExtensions.isEmpty())
            assertEquals(9L, restored.createdAtEpochMillis)
        }
    }

    @Test fun duplicateCompetitionWrongKindVersionAndOwnershipAreRejected() {
        val body = LocalDataBackupCodec.encode(snapshot(), registry).toString(Charsets.UTF_8).substringBefore("SHA256|")
        val row = body.lineSequence().first { it.startsWith("COMPETITION_MATCH|") }
        val fields = row.split('|')
        val invalid = listOf(
            body + "$row\n",
            body.replace(row, fields.toMutableList().also { it[1] = GameType.LANZHOU_MAHJONG.code.toString() }.joinToString("|")),
            body.replace(row, fields.toMutableList().also { it[2] = "2" }.joinToString("|")),
            body.replace(row, fields.toMutableList().also { it[3] = "3" }.joinToString("|")),
            body.replace("MOCS-BACKUP|4", "MOCS-BACKUP|3"),
        )
        for (candidate in invalid) assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.decode(signed(candidate), registry)
        }
    }

    @Test fun damagedPrivateBytesAreRejectedEvenWithARecomputedOuterChecksum() {
        val body = LocalDataBackupCodec.encode(snapshot(), registry).toString(Charsets.UTF_8).substringBefore("SHA256|")
        val row = body.lineSequence().first { it.startsWith("COMPETITION_MATCH|") }
        val fields = row.split('|').toMutableList().also { it[6] = "010203" }
        assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.decode(signed(body.replace(row, fields.joinToString("|"))), registry)
        }
    }

    private fun snapshot(): LocalDataSnapshot {
        val config = matchConfig()
        val record = CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 0, matchRecord(0))
        val archive = CompetitionMahjongMatchArchive(0, config.matchId, 2, CompetitionMahjongMatchCheckpointCodec.encode(record))
        return LocalDataSnapshot(9, gameExtensions = listOf(adapter.encodeArchive(archive)))
    }

    private fun signed(body: String): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return (body + "SHA256|$hash\n").toByteArray()
    }
}
