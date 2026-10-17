package com.masterofchessstrategy.games.go

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real native agreement + durable file + VM recreation; not an OS process-death test. */
@RunWith(AndroidJUnit4::class)
class GoLocalSessionIdentityInstrumentedTest {
    @Test fun terminalUndoReconfirmationAndReopenKeepIndependentRecordIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "go-identity-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val file = File(directory, "session.bin")
        val repository = GoSessionFileRepository(file)
        val store = ViewModelStore()
        suspend fun open(): GoGameViewModel = withContext(Dispatchers.Main) {
            GoGameViewModel(repository, 9).also { store.put("identity", it) }
        }
        suspend fun GoGameViewModel.awaitState(predicate: (GoGameUiState) -> Boolean) = withTimeout(10_000) {
            state.first { !it.loading && !it.saving && !it.needsSave && predicate(it) }
        }
        try {
            val game = open()
            game.awaitState { it.canAct }
            val original = repository.load()!!.identity
            withContext(Dispatchers.Main) { game.pass() }
            game.awaitState { it.currentPlayer == GoPlayer.WHITE }
            withContext(Dispatchers.Main) { game.pass() }
            game.awaitState { it.phase == GoPhase.SCORING }
            withContext(Dispatchers.Main) { game.confirm(GoPlayer.BLACK) }
            game.awaitState { it.blackConfirmed }
            withContext(Dispatchers.Main) { game.confirm(GoPlayer.WHITE) }
            game.awaitState { it.phase == GoPhase.FINISHED }
            assertEquals(original, repository.load()!!.identity)
            withContext(Dispatchers.Main) { game.undo() }
            game.awaitState { it.phase == GoPhase.SCORING }
            val branch = repository.load()!!.identity
            assertEquals(original.afterTerminalUndo(), branch)
            assertNotEquals(original.recordId, branch.recordId)
            withContext(Dispatchers.Main) { game.confirm(GoPlayer.WHITE) }
            game.awaitState { it.phase == GoPhase.FINISHED }
            assertEquals(branch, repository.load()!!.identity)
            val savedBytes = file.readBytes()
            withContext(Dispatchers.Main) { store.clear() }
            val reopened = open()
            reopened.awaitState { it.phase == GoPhase.FINISHED }
            assertEquals(branch, repository.load()!!.identity)
            assertArrayEquals(savedBytes, file.readBytes())
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { store.clear() }
            directory.listFiles()?.forEach { assertTrue(it.delete()) }
            assertTrue(directory.delete())
        }
    }
}
