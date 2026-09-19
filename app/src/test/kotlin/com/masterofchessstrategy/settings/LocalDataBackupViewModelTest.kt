package com.masterofchessstrategy.settings

import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.LocalDataRestoreSummary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
