package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import com.masterofchessstrategy.games.competition_mahjong.match.matchFrame
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongAssistanceUsage
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistory
import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistoryCodec
import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistoryEntry
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongSeriesOutcome
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongStatistics
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CompetitionMahjongFormalRepositoryTest {
    private fun repository(storage: FormalMemoryStorage, clock: () -> Long = { 100 },
                           admit: suspend (CompetitionMahjongMatchCheckpoint) -> Unit = {}):
        CompetitionMahjongFormalRepository = CompetitionMahjongFormalRepository(storage, 0, formalPolicy(),
        admit, clock, Dispatchers.Unconfined)

    @Test fun oneCommitContainsActiveStatisticsAndSixteenHandHistory() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        repo.matchStore.save(formalCheckpoint(count = 15))
        assertEquals(0, repo.statisticsStore.load(0).seriesCount)
        repo.matchStore.save(formalCheckpoint())
        assertEquals(2, disk.writes)
        val reopened = repository(disk)
        assertTrue(reopened.matchStore.load(0)!!.ledger.isComplete)
        assertEquals(1, reopened.statisticsStore.load(0).seriesCount)
        assertEquals(16, reopened.historyStore.load(0).entries.single().hands.size)
    }

    @Test fun failureBeforeCommitPreservesWholePreviousState() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        repo.matchStore.save(formalCheckpoint(count = 15))
        val before = disk.read()
        disk.failBeforeCommit = true
        assertThrows(IOException::class.java) { runBlocking { repo.matchStore.save(formalCheckpoint()) } }
        assertArrayEquals(before, disk.read())
        val reopened = repository(disk)
        assertFalse(reopened.matchStore.load(0)!!.ledger.isComplete)
        assertEquals(0, reopened.statisticsStore.load(0).seriesCount)
        assertTrue(reopened.historyStore.load(0).entries.isEmpty())
    }

    @Test fun lostAcknowledgementAfterCommitRetriesWithoutNewTimestampOrDuplicate() = runBlocking {
        val disk = FormalMemoryStorage().apply { failAfterCommit = true }
        assertThrows(IOException::class.java) { runBlocking { repository(disk).matchStore.save(formalCheckpoint()) } }
        disk.failAfterCommit = false
        val reopened = repository(disk, clock = { 9999 })
        reopened.matchStore.save(formalCheckpoint())
        assertEquals(1, disk.writes)
        assertEquals(1, reopened.statisticsStore.load(0).seriesCount)
        assertEquals(100, reopened.historyStore.load(0).entries.single().settledAtEpochMillis)
    }

    @Test fun deletedSheetStaysDeletedAcrossLoadDuplicateSaveUndoAndRecompletion() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        val complete = formalCheckpoint()
        val id = complete.config.matchId
        repo.matchStore.save(complete)
        repo.historyStore.delete(0, id)
        val reopened = repository(disk)
        reopened.matchStore.load(0)
        reopened.matchStore.save(complete)
        assertEquals(1, reopened.statisticsStore.load(0).seriesCount)
        assertTrue(reopened.historyStore.load(0).entries.isEmpty())
        reopened.matchStore.save(formalCheckpoint(count = 15))
        assertEquals(0, reopened.statisticsStore.load(0).seriesCount)
        reopened.matchStore.save(formalCheckpoint(drawLast = true))
        assertEquals(480, reopened.statisticsStore.load(0).cumulativeScore)
        assertTrue(reopened.historyStore.load(0).entries.isEmpty())
    }

    @Test fun deletionFailureAndLostAcknowledgementAreAtomicAndRetryable() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        val complete = formalCheckpoint()
        repo.matchStore.save(complete)
        disk.failBeforeCommit = true
        assertThrows(IOException::class.java) { runBlocking { repo.historyStore.delete(0, complete.config.matchId) } }
        assertEquals(1, repo.historyStore.load(0).entries.size)
        disk.failBeforeCommit = false
        disk.failAfterCommit = true
        assertThrows(IOException::class.java) { runBlocking { repo.historyStore.delete(0, complete.config.matchId) } }
        disk.failAfterCommit = false
        val before = disk.writes
        repository(disk).historyStore.delete(0, complete.config.matchId)
        assertEquals(before, disk.writes)
        assertEquals(1, repo.statisticsStore.load(0).seriesCount)
        assertTrue(repo.historyStore.load(0).entries.isEmpty())
    }

    @Test fun finalUndoAndRecompletionReplaceOnlyThatResultInOneCommit() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        repo.matchStore.save(formalCheckpoint(id = 1))
        repo.matchStore.save(formalCheckpoint(id = 2))
        repo.matchStore.save(formalCheckpoint(id = 2, count = 15))
        assertEquals(1, repo.statisticsStore.load(0).seriesCount)
        assertEquals(formalCheckpoint(id = 1).config.matchId, repo.historyStore.load(0).entries.single().matchId)
        repo.matchStore.save(formalCheckpoint(id = 2, drawLast = true))
        assertEquals(2, repo.statisticsStore.load(0).seriesCount)
        assertEquals(992, repo.statisticsStore.load(0).cumulativeScore)
    }

    @Test fun undoFailureKeepsCompletedActiveAndBothResults() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        repo.matchStore.save(formalCheckpoint())
        val before = disk.read()
        disk.failBeforeCommit = true
        assertThrows(IOException::class.java) { runBlocking { repo.matchStore.save(formalCheckpoint(count = 15)) } }
        assertArrayEquals(before, disk.read())
        assertTrue(repo.matchStore.load(0)!!.ledger.isComplete)
        assertEquals(1, repo.statisticsStore.load(0).seriesCount)
        assertEquals(1, repo.historyStore.load(0).entries.size)
    }

    @Test fun conflictWithoutUndoAndReuseOfOlderArchivedIdAreRejected() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        repo.matchStore.save(formalCheckpoint())
        val before = disk.read()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.matchStore.save(formalCheckpoint(drawLast = true)) } }
        assertArrayEquals(before, disk.read())
        repo.matchStore.save(formalCheckpoint(id = 2, count = 0))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.matchStore.save(formalCheckpoint(count = 15)) } }
        assertEquals(1, repo.statisticsStore.load(0).seriesCount)
    }

    @Test fun separateInstancesMergeFavoriteAndNewCompletion() = runBlocking {
        val disk = FormalMemoryStorage()
        val first = repository(disk)
        val second = repository(disk)
        val complete = formalCheckpoint()
        first.matchStore.save(complete)
        coroutineScope {
            listOf(launch { first.historyStore.setFavorite(0, complete.config.matchId, true) },
                launch { second.matchStore.save(formalCheckpoint(id = 2)) }).joinAll()
        }
        val entries = repository(disk).historyStore.load(0).entries
        assertEquals(2, entries.size)
        assertEquals(complete.config.matchId, entries.first().matchId)
        assertTrue(entries.first().favorite)
    }

    @Test fun rejectedNativeAdmissionCannotWriteOrPublishStoredData() = runBlocking {
        val disk = FormalMemoryStorage()
        val rejected = repository(disk, admit = { throw IOException("Native rejected") })
        assertThrows(IOException::class.java) { runBlocking { rejected.matchStore.save(formalCheckpoint()) } }
        assertNull(disk.bytes)
        repository(disk).matchStore.save(formalCheckpoint())
        val before = disk.read()
        assertThrows(IOException::class.java) { runBlocking { rejected.matchStore.load(0) } }
        assertThrows(IOException::class.java) { runBlocking { rejected.statisticsStore.load(0) } }
        assertThrows(IOException::class.java) { runBlocking { rejected.historyStore.delete(0, formalCheckpoint().config.matchId) } }
        assertArrayEquals(before, disk.read())
    }

    @Test fun foreignOwnerAndDerivedWritePathsAreRejected() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.matchStore.save(formalCheckpoint(viewer = 1)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.statisticsStore.load(1) } }
        repo.matchStore.save(formalCheckpoint())
        assertThrows(UnsupportedOperationException::class.java) { runBlocking {
            repo.statisticsStore.withdraw(0, formalCheckpoint().config.matchId)
        } }
        assertThrows(UnsupportedOperationException::class.java) { runBlocking {
            repo.historyStore.withdraw(0, formalCheckpoint().config.matchId)
        } }
        assertEquals(1, repo.statisticsStore.load(0).seriesCount)
    }

    @Test fun corruptFileIsPreservedAndCannotBeOverwrittenByFreshSave() = runBlocking {
        val disk = FormalMemoryStorage().apply { bytes = byteArrayOf(1, 2, 3) }
        val before = disk.read()
        val repo = repository(disk)
        assertThrows(IOException::class.java) { runBlocking { repo.matchStore.load(0) } }
        assertThrows(IOException::class.java) { runBlocking { repo.matchStore.save(formalCheckpoint()) } }
        assertArrayEquals(before, disk.read())
        assertEquals(0, disk.writes)
    }

    @Test fun persistedAssistanceCannotBeResetByDuplicateDelivery() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        repo.matchStore.save(formalCheckpoint().withAssistance(CompetitionMahjongAssistanceUsage(Difficulty.HARD, undoUsed = 1)))
        val before = disk.read()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.matchStore.save(formalCheckpoint()) } }
        assertArrayEquals(before, disk.read())
    }

    @Test fun activeSeriesCannotSkipHandsOrRewriteEarlierHandResults() = runBlocking {
        val disk = FormalMemoryStorage()
        val repo = repository(disk)
        repo.matchStore.save(formalCheckpoint(count = 0))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.matchStore.save(formalCheckpoint(count = 15)) } }
        val laterDisk = FormalMemoryStorage()
        val later = repository(laterDisk)
        val checkpoint = formalCheckpoint(count = 15)
        later.matchStore.save(checkpoint)
        val changed = (0 until 15).fold(CompetitionMahjongMatchLedger.empty()) { ledger, index ->
            ledger.recordTerminal(checkpoint.config, index, matchFrame(checkpoint.config, index, draw = index == 0))
        }
        val different = CompetitionMahjongMatchCheckpoint(checkpoint.config, changed, 15,
            checkpoint.engineRecord, checkpoint.assistance)
        val before = laterDisk.read()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { later.matchStore.save(different) } }
        assertArrayEquals(before, laterDisk.read())
    }

    @Test fun missingDifficultyOrLockedPolicyCannotCreateTransactionFile() = runBlocking {
        val disk = FormalMemoryStorage()
        val checkpoint = formalCheckpoint()
        val legacy = CompetitionMahjongMatchCheckpoint(checkpoint.config, checkpoint.ledger,
            checkpoint.activeHandIndex, checkpoint.engineRecord)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository(disk).matchStore.save(legacy) } }
        val easyOnly = CompetitionMahjongPlayPolicy.fromProgress(CompetitionMahjongUnlockProgress(true),
            Difficulty.entries.associateWith { it.code })
        val locked = CompetitionMahjongFormalRepository(disk, 0, easyOnly, {}, dispatcher = Dispatchers.Unconfined)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { locked.matchStore.save(checkpoint) } }
        assertNull(disk.bytes)
        assertEquals(0, disk.writes)
    }

    @Test fun fullHistoryRejectsWholeCompletionUntilExplicitDeletionFreesASlot() = runBlocking {
        val checkpoint = formalCheckpoint()
        val baseOutcome = CompetitionMahjongSeriesOutcome.from(checkpoint, formalPolicy(), 100)
        val baseHistory = CompetitionMahjongMatchHistoryEntry.from(checkpoint, formalPolicy(), 100)
        fun id(index: Int) = "97000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
        val ids = (1..CompetitionMahjongMatchHistoryCodec.MAX_RECORDS).map(::id)
        val initial = CompetitionMahjongFormalSnapshot(0,
            statistics = CompetitionMahjongStatistics.from(ids.map { baseOutcome.copy(matchId = it) }),
            history = CompetitionMahjongMatchHistory.from(ids.map { CompetitionMahjongMatchHistoryEntry(
                it, 0, baseHistory.difficulty, baseHistory.botLevel, 100, false, baseHistory.hands) }))
        val disk = FormalMemoryStorage().apply { bytes = CompetitionMahjongFormalSnapshotCodec.encode(initial) }
        val before = disk.read()
        val repo = repository(disk)
        val next = formalCheckpoint(id = CompetitionMahjongMatchHistoryCodec.MAX_RECORDS + 1)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.matchStore.save(next) } }
        assertArrayEquals(before, disk.read())
        assertNull(repo.matchStore.load(0))
        repo.historyStore.delete(0, ids.first())
        repo.matchStore.save(next)
        assertEquals(CompetitionMahjongMatchHistoryCodec.MAX_RECORDS + 1, repo.statisticsStore.load(0).seriesCount)
        assertEquals(CompetitionMahjongMatchHistoryCodec.MAX_RECORDS, repo.historyStore.load(0).entries.size)
    }
}
