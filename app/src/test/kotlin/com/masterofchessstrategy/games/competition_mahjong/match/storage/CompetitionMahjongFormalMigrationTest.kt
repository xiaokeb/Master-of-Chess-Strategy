package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.games.competition_mahjong.history.*
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.statistics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CompetitionMahjongFormalMigrationTest {
    private fun repository(disk: FormalMemoryStorage,
                           admit: suspend (CompetitionMahjongMatchCheckpoint) -> Unit = {}) =
        CompetitionMahjongFormalRepository(disk, 0, formalPolicy(), admit, dispatcher = Dispatchers.Unconfined)

    private fun source(checkpoint: CompetitionMahjongMatchCheckpoint? = formalCheckpoint(),
                       statistics: ByteArray? = null, history: ByteArray? = null,
                       materialize: Boolean = true, deleted: Set<String> = emptySet(), time: Long = 100) =
        CompetitionMahjongFormalMigrationSource(0, checkpoint?.let(CompetitionMahjongMatchCheckpointCodec::encode),
            statistics, history, materialize, time, deleted)

    @Test fun previewWritesNothingAndCompletedMigrationAtomicallyBuildsBothViews() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        val old = source()
        val original = old.activeBytes!!
        val preview = repo.migration.preview { old }
        assertEquals(1, preview.statisticsCount)
        assertEquals(1, preview.visibleHistoryCount)
        assertTrue(preview.materializedActiveCompletion)
        assertNull(disk.bytes)
        val result = repo.migration.migrate(preview.sourceSha256) { old }
        assertTrue(result.wasInstalled)
        assertEquals(1, disk.writes)
        assertTrue(repo.matchStore.load(0)!!.ledger.isComplete)
        assertEquals(1, repo.statisticsStore.load(0).seriesCount)
        assertEquals(1, repo.historyStore.load(0).entries.size)
        assertArrayEquals(original, old.activeBytes)
    }

    @Test fun incompleteMigrationCreatesNoFormalResultAndLegacyV1IsRejected() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        val old = source(formalCheckpoint(count = 5), materialize = false)
        val preview = repo.migration.preview { old }
        repo.migration.migrate(preview.sourceSha256) { old }
        assertEquals(5, repo.matchStore.load(0)!!.activeHandIndex)
        assertEquals(0, repo.statisticsStore.load(0).seriesCount)
        val checkpoint = formalCheckpoint()
        val legacy = CompetitionMahjongMatchCheckpoint(checkpoint.config, checkpoint.ledger, 15, checkpoint.engineRecord)
        val empty = FormalMemoryStorage()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository(empty).migration.preview { source(legacy) } } }
        assertNull(empty.bytes)
    }

    @Test fun alreadyPopulatedViewsPreserveTimestampFavoriteAndDeletedHistory() = runBlocking {
        val first = formalCheckpoint()
        val second = formalCheckpoint(id = 2)
        val stats = listOf(first, second).map { CompetitionMahjongSeriesOutcome.from(it, formalPolicy(), 33) }
        val sheet = CompetitionMahjongMatchHistoryEntry.from(first, formalPolicy(), 33).withFavorite(true)
        val old = source(second,
            CompetitionMahjongStatisticsCodec.encode(0, stats), CompetitionMahjongMatchHistoryCodec.encode(0, listOf(sheet)),
            deleted = setOf(second.config.matchId))
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        val preview = repo.migration.preview { old }
        assertEquals(1, preview.confirmedDeletionCount)
        repo.migration.migrate(preview.sourceSha256) { old }
        assertEquals(2, repo.statisticsStore.load(0).seriesCount)
        assertEquals(33, repo.statisticsStore.load(0).outcomes.first().settledAtEpochMillis)
        assertTrue(repo.historyStore.load(0).entries.single().favorite)
        repo.matchStore.save(second)
        assertEquals(1, repo.historyStore.load(0).entries.size)
    }

    @Test fun missingHistoryWithUnknownDeletionStatusIsNeverGuessed(): Unit = runBlocking {
        val complete = formalCheckpoint()
        val stats = CompetitionMahjongStatisticsCodec.encode(0, listOf(
            CompetitionMahjongSeriesOutcome.from(complete, formalPolicy(), 100)))
        val disk = FormalMemoryStorage()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            repository(disk).migration.preview { source(complete, statistics = stats) }
        } }
        assertNull(disk.bytes)
        val orphanStats = source(null, statistics = stats, materialize = false)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository(disk).migration.preview { orphanStats } } }
    }

    @Test fun materializingCurrentCompletionMustBeExplicitAndCannotResolveConflicts() = runBlocking {
        val disk = FormalMemoryStorage()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            repository(disk).migration.preview { source(materialize = false) }
        } }
        val conflicting = CompetitionMahjongStatisticsCodec.encode(0, listOf(
            CompetitionMahjongSeriesOutcome.from(formalCheckpoint(drawLast = true), formalPolicy(), 100)))
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            repository(disk).migration.preview { source(statistics = conflicting, deleted = setOf(formalCheckpoint().config.matchId)) }
        } }
        assertNull(disk.bytes)
    }

    @Test fun sourceChangedSincePreviewOrDuringAdmissionPreventsTargetWrite() = runBlocking {
        val disk = FormalMemoryStorage()
        val first = source()
        val second = source(formalCheckpoint(id = 2))
        val repo = repository(disk)
        val preview = repo.migration.preview { first }
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            repo.migration.migrate(preview.sourceSha256) { second }
        } }
        var reads = 0
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            repo.migration.migrate(preview.sourceSha256) { if (reads++ == 0) first else second }
        } }
        assertEquals(2, reads)
        assertNull(disk.bytes)
    }

    @Test fun changedMigrationChoiceHasDifferentSourceIdentity() {
        assertNotEquals(source().sha256, source(materialize = false).sha256)
        assertNotEquals(source().sha256, source(time = 101).sha256)
        assertNotEquals(source().sha256, source(deleted = setOf(formalCheckpoint().config.matchId)).sha256)
    }

    @Test fun existingDifferentOrCorruptTargetIsPreserved() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        val old = source(formalCheckpoint(id = 2))
        val preview = repo.migration.preview { old }
        repo.matchStore.save(formalCheckpoint())
        val before = disk.read()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.migration.migrate(preview.sourceSha256) { old } } }
        assertArrayEquals(before, disk.read())
        disk.bytes = byteArrayOf(1, 2, 3)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.migration.migrate(preview.sourceSha256) { old } } }
        assertArrayEquals(byteArrayOf(1, 2, 3), disk.read())
    }

    @Test fun lostAcknowledgementAfterMigrationCommitsRetriesAsAlreadyInstalled() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        val old = source()
        val preview = repo.migration.preview { old }
        disk.failBeforeCommit = true
        assertThrows(IOException::class.java) { runBlocking { repo.migration.migrate(preview.sourceSha256) { old } } }
        assertNull(disk.bytes)
        disk.failBeforeCommit = false
        disk.failAfterCommit = true
        assertThrows(IOException::class.java) { runBlocking { repo.migration.migrate(preview.sourceSha256) { old } } }
        disk.failAfterCommit = false
        val result = repository(disk).migration.migrate(preview.sourceSha256) { old }
        assertFalse(result.wasInstalled)
        assertEquals(2, disk.writes)
        assertEquals(1, repo.statisticsStore.load(0).seriesCount)
    }

    @Test fun rejectedNativeAdmissionAndForeignOwnerPreserveAllData() = runBlocking {
        val disk = FormalMemoryStorage()
        val old = source()
        val preview = repository(disk).migration.preview { old }
        val rejected = repository(disk) { throw IOException("Native rejected") }
        assertThrows(IOException::class.java) { runBlocking { rejected.migration.migrate(preview.sourceSha256) { old } } }
        val foreign = CompetitionMahjongFormalMigrationSource(1, null, null, null, false, 0)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository(disk).migration.preview { foreign } } }
        assertNull(disk.bytes)
    }

    @Test fun admittedFinalUndoRetractsStaleResultButEarlierSnapshotsCannotEraseIt(): Unit = runBlocking {
        val complete = formalCheckpoint()
        val statistics = CompetitionMahjongStatisticsCodec.encode(0, listOf(
            CompetitionMahjongSeriesOutcome.from(complete, formalPolicy(), 100)))
        val history = CompetitionMahjongMatchHistoryCodec.encode(0, listOf(
            CompetitionMahjongMatchHistoryEntry.from(complete, formalPolicy(), 100)))
        val old = source(formalCheckpoint(count = 15), statistics, history, materialize = false)
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        val preview = repo.migration.preview { old }
        assertTrue(preview.retractedActiveResult)
        repo.migration.migrate(preview.sourceSha256) { old }
        assertEquals(0, repo.statisticsStore.load(0).seriesCount)
        assertTrue(repo.historyStore.load(0).entries.isEmpty())
        val early = source(formalCheckpoint(count = 0), statistics, history, materialize = false)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.migration.preview { early } } }
    }

    @Test fun sourceOwnsByteArraysAndRejectsCorruptEncodedSections() = runBlocking {
        val bytes = CompetitionMahjongMatchCheckpointCodec.encode(formalCheckpoint())
        val old = CompetitionMahjongFormalMigrationSource(0, bytes, null, null, true, 100)
        val digest = old.sha256
        bytes.fill(0)
        old.activeBytes!!.fill(0)
        assertEquals(digest, old.sha256)
        val disk = FormalMemoryStorage()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            repository(disk).migration.preview { source(statistics = byteArrayOf(1)) }
        } }
        assertNull(disk.bytes)
    }
}
