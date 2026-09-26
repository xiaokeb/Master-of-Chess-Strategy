package com.masterofchessstrategy.settings

import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.LocalDataRestoreSummary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDataBackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiStateParticipatesInComposeSnapshotObservation() {
        val viewModel = LocalDataBackupViewModel(FakeBackupRepository(), ioDispatcher = dispatcher)
        var observedReads = 0
        Snapshot.observe(readObserver = { observedReads++ }) { viewModel.uiState }
        assertTrue(observedReads > 0)
    }

    @Test
    fun restoreWaitsForWritersAndRefreshesDataBeforeSuccessCallback() = runTest(dispatcher) {
        val barrier = CompletableDeferred<Unit>()
        val repository = FakeBackupRepository()
        val viewModel = LocalDataBackupViewModel(repository, ioDispatcher = dispatcher)
        val events = mutableListOf<String>()
        viewModel.restore(
            openInputStream = { ByteArrayInputStream(byteArrayOf(4)) },
            beforeRestore = { barrier.await() },
            onFinished = { events += "reload" },
            onRestored = { events += "success" },
        )
        runCurrent()
        assertTrue(viewModel.uiState.isWorking)
        assertEquals(null, repository.restored)
        // Double-clicks cannot start a second operation while the barrier is pending.
        viewModel.restore({ error("must not open another document") }, { error("duplicate restore") })
        barrier.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("reload", "success"), events)
        assertArrayEquals(byteArrayOf(4), repository.restored)
    }

    @Test
    fun failedRestoreReloadsUnchangedDataWithoutSuccessCallback() = runTest(dispatcher) {
        val viewModel = LocalDataBackupViewModel(FakeBackupRepository(), ioDispatcher = dispatcher)
        var reloaded = false
        viewModel.restore(
            openInputStream = { throw IOException("unavailable") },
            onRestored = { error("must not report success") },
            onFinished = { reloaded = true },
        )
        advanceUntilIdle()
        assertTrue(reloaded)
        assertEquals(BackupFeedback.RESTORE_FAILED, viewModel.uiState.feedback)
    }

    @Test
    fun exportWritesExactlyTheRepositoryPayload() = runTest(dispatcher) {
        val repository = FakeBackupRepository(exported = byteArrayOf(1, 2, 3))
        val output = ByteArrayOutputStream()
        val viewModel = LocalDataBackupViewModel(
            repository = repository,
            nowEpochMillis = { 99L },
            ioDispatcher = dispatcher,
        )

        viewModel.export { output }
        assertTrue(viewModel.uiState.isWorking)
        advanceUntilIdle()

        assertEquals(99L, repository.exportTimestamp)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertEquals(BackupFeedback.EXPORTED, viewModel.uiState.feedback)
        assertFalse(viewModel.uiState.isWorking)
    }

    @Test
    fun successfulRestoreInvokesRefreshOnlyAfterRepositoryCommit() = runTest(dispatcher) {
        val repository = FakeBackupRepository()
        val viewModel = LocalDataBackupViewModel(
            repository = repository,
            ioDispatcher = dispatcher,
        )
        var refreshed = false

        viewModel.restore(
            openInputStream = { ByteArrayInputStream(byteArrayOf(4, 5)) },
            onRestored = { refreshed = true },
        )
        assertFalse(refreshed)
        advanceUntilIdle()

        assertArrayEquals(byteArrayOf(4, 5), repository.restored)
        assertTrue(refreshed)
        assertEquals(BackupFeedback.RESTORED, viewModel.uiState.feedback)
        assertEquals(1, viewModel.uiState.restoreSummary?.gameRecordCount)
    }

    @Test
    fun streamFailureDoesNotInvokeRestoreOrRefresh() = runTest(dispatcher) {
        val repository = FakeBackupRepository()
        val viewModel = LocalDataBackupViewModel(
            repository = repository,
            ioDispatcher = dispatcher,
        )
        var refreshed = false

        viewModel.restore(
            openInputStream = { throw IOException("document unavailable") },
            onRestored = { refreshed = true },
        )
        advanceUntilIdle()

        assertFalse(refreshed)
        assertEquals(null, repository.restored)
        assertEquals(BackupFeedback.RESTORE_FAILED, viewModel.uiState.feedback)
    }

    private class FakeBackupRepository(
        private val exported: ByteArray = byteArrayOf(7),
    ) : LocalDataBackupRepository {
        var exportTimestamp: Long? = null
        var restored: ByteArray? = null

        override suspend fun export(createdAtEpochMillis: Long): ByteArray {
            exportTimestamp = createdAtEpochMillis
            return exported.copyOf()
        }

        override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary {
            restored = bytes.copyOf()
            return LocalDataRestoreSummary(
                activeSessionCount = 1,
                matchOutcomeCount = 1,
                gameRecordCount = 1,
                completedEndgameCount = 1,
            )
        }
    }
}
