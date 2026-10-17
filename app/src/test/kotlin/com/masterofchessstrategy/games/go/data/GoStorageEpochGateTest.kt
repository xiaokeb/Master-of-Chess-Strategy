package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.LocalDataRestoreSummary
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** Coroutine storage ordering only; Native admission and Room rollback have separate device tests. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoStorageEpochGateTest {
    @Test fun sameDatabaseKeepsItsEpochAcrossHostRecreation() = runTest {
        val database = Any()
        val first = GoStorageEpochGate.forDatabase(database)
        assertSame(first, GoStorageEpochGate.forDatabase(database))
        first.backup(replacing = true) { }
        assertEquals(1L, GoStorageEpochGate.forDatabase(database).epoch.value)
        assertEquals(0L, GoStorageEpochGate.forDatabase(Any()).epoch.value)
    }

    @Test fun replacementWaitsForAnAlreadyRunningNonCancellableWrite() = runTest {
        val gate = GoStorageEpochGate()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val writer = launch { gate.access(0) { withContext(NonCancellable) {
            events += "save-start"; release.await(); events += "save-end"
        } } }
        runCurrent()
        writer.cancel()
        val replacement = launch { gate.backup(true) { events += "restore" } }
        runCurrent()
        assertEquals(listOf("save-start"), events)
        assertEquals(0L, gate.epoch.value)
        release.complete(Unit); writer.join(); replacement.join()
        assertEquals(listOf("save-start", "save-end", "restore"), events)
        assertEquals(1L, gate.epoch.value)
    }

    @Test fun exportKeepsWritersBlockedAcrossPreparationGapAndSnapshot() = runTest {
        val gate = GoStorageEpochGate()
        val finishPreparation = CompletableDeferred<Unit>()
        val finishSnapshot = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val preparation = object : GoBackupPreparation {
            override suspend fun beforeExport() { events += "prepare"; finishPreparation.await() }
            override suspend fun beforeRestore() = Unit
        }
        val delegate = object : LocalDataBackupRepository {
            override suspend fun export(createdAtEpochMillis: Long): ByteArray {
                events += "snapshot"; finishSnapshot.await(); return byteArrayOf(7)
            }
            override suspend fun restore(bytes: ByteArray) = summary()
        }
        val backup = GatedGoBackupRepository(delegate, gate, preparation)
        val exporter = launch { assertArrayEquals(byteArrayOf(7), backup.export(1)) }
        runCurrent()
        val writer = launch { gate.access(0) { events += "writer" } }
        runCurrent(); assertEquals(listOf("prepare"), events)
        finishPreparation.complete(Unit); runCurrent()
        assertEquals(listOf("prepare", "snapshot"), events)
        finishSnapshot.complete(Unit); exporter.join(); writer.join()
        assertEquals(listOf("prepare", "snapshot", "writer"), events)
        assertEquals(0L, gate.epoch.value)
    }

    @Test fun restoreInvalidatesEveryOldLocalAndAiRepositoryBeforeTheyCanCallDelegates() = runTest {
        val gate = GoStorageEpochGate()
        var calls = 0
        val sessions = object : GoSessionRepository {
            override suspend fun load(): GoSavedSession? { calls++; return null }
            override suspend fun save(session: GoSavedSession) { calls++ }
            override suspend fun clear() { calls++ }
        }
        val lessons = object : GoLearningProgressRepository {
            override suspend fun load(): GoLearningProgress { calls++; return GoLearningProgress() }
            override suspend fun save(progress: GoLearningProgress) { calls++ }
        }
        val endgames = object : GoEndgameProgressRepository {
            override suspend fun load(): GoEndgameProgress { calls++; return GoEndgameProgress() }
            override suspend fun select(id: String, mode: GoEndgameChallengeMode): GoEndgameProgress { calls++; return GoEndgameProgress() }
            override suspend fun selectDaily(date: LocalDate, id: String): GoEndgameProgress { calls++; return GoEndgameProgress() }
            override suspend fun complete(id: String): GoEndgameProgress { calls++; return GoEndgameProgress() }
        }
        val ai = object : GoAiMatchRepository {
            override suspend fun load(): GoAiSavedMatch? { calls++; return null }
            override suspend fun save(match: GoAiSavedMatch) { calls++ }
        }
        val oldSession = GatedGoSessionRepository(sessions, gate)
        val oldLearning = GatedGoLearningRepository(lessons, gate)
        val oldEndgame = GatedGoEndgameRepository(endgames, gate)
        val oldAi = GatedGoAiMatchRepository(ai, gate)
        gate.backup(true) { }
        rejected { oldSession.load() }; rejected { oldSession.save(saved()) }; rejected { oldSession.clear() }
        rejected { oldLearning.load() }; rejected { oldLearning.save(GoLearningProgress()) }
        val id = GoEndgameLibrary.puzzles.first().id
        rejected { oldEndgame.load() }; rejected { oldEndgame.select(id, GoEndgameChallengeMode.MAINLINE) }
        rejected { oldEndgame.selectDaily(LocalDate.of(2026, 10, 16), id) }; rejected { oldEndgame.complete(id) }
        rejected { oldAi.load() }; rejected { oldAi.save(GoAiSavedMatch("old-ai", GoAiMatchConfig(), byteArrayOf(1))) }
        assertEquals(0, calls)
        assertNull(GatedGoSessionRepository(sessions, gate).load())
        assertEquals(1, calls)
    }

    @Test fun aNewOwnerCreatedDuringRestoreWaitsUntilTheReplacementCommits() = runTest {
        val gate = GoStorageEpochGate()
        val commit = CompletableDeferred<Unit>()
        var value = "old"
        val replacing = launch { gate.backup(true) { commit.await(); value = "new" } }
        runCurrent()
        val epoch = gate.epoch.value
        var loaded: String? = null
        val reader = launch { loaded = gate.access(epoch) { value } }
        runCurrent(); assertNull(loaded)
        commit.complete(Unit); replacing.join(); reader.join()
        assertEquals("new", loaded)
    }

    @Test fun failedRestoreRetiresOldPagesButReleasesTheGateForReloadingUnchangedData() = runTest {
        val gate = GoStorageEpochGate()
        val old = gate.epoch.value
        try { gate.backup(true) { throw IOException("restore rejected") }; fail("must fail") }
        catch (_: IOException) { }
        rejected { gate.access(old) { fail("old page cannot resume") } }
        assertEquals("retained", gate.access(gate.epoch.value) { "retained" })
    }

    @Test fun cancelledRestoreWaitingForTheGateDoesNotAdvanceTheEpoch() = runTest {
        val gate = GoStorageEpochGate()
        val release = CompletableDeferred<Unit>()
        val owner = launch { gate.access(0) { release.await() } }
        runCurrent()
        val replacement = launch { gate.backup(true) { fail("cancelled before lock") } }
        runCurrent(); replacement.cancel(); replacement.join()
        assertEquals(0L, gate.epoch.value)
        release.complete(Unit); owner.join()
        assertEquals(1, gate.access(0) { 1 })
    }

    @Test fun cancelledActiveRestoreRetainsItsNewEpochAndAlwaysReleasesTheMutex() = runTest {
        val gate = GoStorageEpochGate()
        val restoring = launch { gate.backup(true) { awaitCancellation() } }
        runCurrent(); assertEquals(1L, gate.epoch.value)
        restoring.cancel(); restoring.join()
        assertEquals(1, gate.access(1) { 1 })
        rejected { gate.access(0) { fail("retired") } }
    }

    @Test fun queuedSessionSaveFreezesCallerBytesBeforeWaiting() = runTest {
        val gate = GoStorageEpochGate()
        val release = CompletableDeferred<Unit>()
        val blocker = launch { gate.backup(false) { release.await() } }
        runCurrent()
        var stored: GoSavedSession? = null
        val repo = GatedGoSessionRepository(object : GoSessionRepository {
            override suspend fun load() = stored
            override suspend fun save(session: GoSavedSession) { stored = session }
            override suspend fun clear() { stored = null }
        }, gate)
        val input = saved()
        val save = launch { repo.save(input) }
        runCurrent(); input.engineState.fill(9)
        release.complete(Unit); blocker.join(); save.join()
        assertArrayEquals(byteArrayOf(1, 2), stored!!.engineState)
        repo.load()!!.engineState.fill(0)
        assertArrayEquals(byteArrayOf(1, 2), stored!!.engineState)
    }

    @Test fun queuedLessonSaveFreezesTheCallerSetBeforeWaiting() = runTest {
        val gate = GoStorageEpochGate()
        val release = CompletableDeferred<Unit>()
        val blocker = launch { gate.backup(false) { release.await() } }
        runCurrent()
        var stored = GoLearningProgress()
        val repo = GatedGoLearningRepository(object : GoLearningProgressRepository {
            override suspend fun load() = stored
            override suspend fun save(progress: GoLearningProgress) { stored = progress }
        }, gate)
        val ids = mutableSetOf("liberties")
        val save = launch { repo.save(GoLearningProgress(ids)) }
        runCurrent(); ids.clear()
        release.complete(Unit); blocker.join(); save.join()
        assertEquals(setOf("liberties"), stored.completedLessonIds)
    }

    @Test fun queuedRestoreFreezesBackupBytesBeforeWaitingForThePreviousWriter() = runTest {
        val gate = GoStorageEpochGate()
        val release = CompletableDeferred<Unit>()
        val blocker = launch { gate.access(0) { release.await() } }
        runCurrent()
        var restored: ByteArray? = null
        val delegate = object : LocalDataBackupRepository {
            override suspend fun export(createdAtEpochMillis: Long) = byteArrayOf(1)
            override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary { restored = bytes; return summary() }
        }
        val backup = GatedGoBackupRepository(delegate, gate, preparation())
        val input = byteArrayOf(3, 4)
        val restoring = launch { backup.restore(input) }
        runCurrent(); input.fill(0)
        release.complete(Unit); blocker.join(); restoring.join()
        assertArrayEquals(byteArrayOf(3, 4), restored)
    }

    @Test fun preparationFailureNeverCallsTheSnapshotOrReplacementAndDoesNotLeaveTheGateLocked() = runTest {
        val gate = GoStorageEpochGate()
        val delegate = object : LocalDataBackupRepository {
            override suspend fun export(createdAtEpochMillis: Long): ByteArray = error("must not export")
            override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary = error("must not restore")
        }
        val preparation = object : GoBackupPreparation {
            override suspend fun beforeExport() { throw IOException("unsupported AI source") }
            override suspend fun beforeRestore() { throw IOException("unsupported AI source") }
        }
        val backup = GatedGoBackupRepository(delegate, gate, preparation)
        rejected { backup.export(1) }; rejected { backup.restore(byteArrayOf(1)) }
        assertEquals(1L, gate.epoch.value)
        assertEquals(1, gate.access(1) { 1 })
    }

    private fun preparation() = object : GoBackupPreparation {
        override suspend fun beforeExport() = Unit
        override suspend fun beforeRestore() = Unit
    }
    private fun saved() = GoSavedSession(9, byteArrayOf(1, 2), GoScoreDisplayMode.STONES)
    private fun summary() = LocalDataRestoreSummary(0, 0, 0, 0)
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("stale/unsupported data owner must fail") }
        catch (_: IOException) { }
    }
}
