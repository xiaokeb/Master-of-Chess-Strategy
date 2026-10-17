package com.masterofchessstrategy.games.go

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Uses the real JNI parser and UUID-only AtomicFile storage; never clears App data. */
@RunWith(AndroidJUnit4::class)
class GoGameRecordInstrumentedTest {
    private val engines = CopyOnWriteArrayList<NativeGoEngine>()
    private val files = mutableListOf<File>()
    private val stores = mutableListOf<ViewModelStore>()

    @After fun cleanup() {
        runBlocking { withContext(Dispatchers.Main) { stores.forEach(ViewModelStore::clear) } }
        engines.forEach(NativeGoEngine::close)
        for (file in files) {
            file.delete()
            File(file.path + ".bak").delete()
            File(file.path + ".new").delete()
        }
    }

    private fun repository(): GoSessionFileRepository {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.getFileStreamPath("go-record-qa-${UUID.randomUUID()}.bin")
        files.add(file)
        return GoSessionFileRepository(file)
    }

    private suspend fun game(repository: GoSessionRepository): GoGameViewModel {
        val game = withContext(Dispatchers.Main) {
            GoGameViewModel(repository, 9, engineFactory = { size, komi, handicap ->
                NativeGoEngine(size, komi, handicap).also(engines::add)
            }).also { model ->
                ViewModelStore().also { store -> store.put("go-record-qa", model); stores.add(store) }
            }
        }
        awaitReady(game)
        return game
    }

    private suspend fun awaitReady(game: GoGameViewModel): GoGameUiState = withTimeout(30_000) {
        game.state.first { !it.loading && !it.saving && !it.importing }.also {
            assertNull(it.error)
            assertTrue(it.sessionReady)
            assertFalse(it.needsSave)
        }
    }

    @Test fun importedHandicapMainlinePersistsAndReopensForAllBoardSizes() = runBlocking {
        for (size in listOf(9, 13, 19)) {
            val text = NativeGoEngine(size, 0, 5).use { fixture ->
                assertEquals(ActionResult.Accepted, fixture.apply(GoAction.Place(BoardPosition(0, 0))))
                assertEquals(ActionResult.Accepted, fixture.apply(GoAction.Pass))
                fixture.exportSgf()
            }
            val repository = repository()
            val game = game(repository)
            withContext(Dispatchers.Main) { game.importSgf(text) }
            val imported = awaitReady(game)
            assertEquals(size, imported.boardSize)
            assertEquals(5, imported.handicapStones)
            assertEquals(2, imported.stones[0])
            assertEquals(0, imported.lastMoveIndex)
            assertEquals(GoPlayer.WHITE, imported.currentPlayer)
            val saved = repository.load()!!
            assertEquals(size, saved.boardSize)
            assertEquals(0, saved.lastMoveIndex)
            NativeGoEngine(size).use { restored ->
                assertEquals(RestoreResult.Restored, restored.restore(saved.engineState))
                assertEquals(5, restored.handicapStones)
                assertEquals(0, restored.blackKomiQuarters)
                assertEquals(text, restored.exportSgf())
            }
            val reopened = game(repository)
            assertEquals(imported.stones, reopened.state.value.stones)
            assertEquals(imported.lastMoveIndex, reopened.state.value.lastMoveIndex)
        }
    }

    @Test fun rejectedMainlineAndFailedSavePreserveActualOldRecord() = runBlocking {
        val disk = repository()
        var failSave = false
        val repository = object : GoSessionRepository {
            override suspend fun load() = disk.load()
            override suspend fun save(session: GoSavedSession) {
                if (failSave) throw IOException("测试写盘失败")
                disk.save(session)
            }
            override suspend fun clear() = disk.clear()
        }
        val game = game(repository)
        withContext(Dispatchers.Main) { game.play(0, 1) }
        awaitReady(game)
        val before = disk.load()!!
        val beforeSgf = withContext(Dispatchers.Main) { game.exportSgf() }
        for (text in listOf("(;FF[4]GM[1]SZ[11])", "(;FF[4]GM[1]SZ[13];B[aa])")) {
            failSave = text.contains("SZ[13]")
            withContext(Dispatchers.Main) { game.importSgf(text) }
            val state = withTimeout(30_000) { game.state.first { !it.importing } }
            assertNotNull(state.error)
            assertEquals(9, state.boardSize)
            assertEquals(1, state.stones[9])
            assertTrue(state.canAct)
            assertEquals(beforeSgf, withContext(Dispatchers.Main) { game.exportSgf() })
            assertArrayEquals(before.engineState, disk.load()!!.engineState)
            assertEquals(before.lastMoveIndex, disk.load()!!.lastMoveIndex)
        }
    }

    @Test fun exportedFinalMainlineImportsAsUnconfirmedAgreementAndUndoKeepsMarker() = runBlocking {
        val game = game(repository())
        withContext(Dispatchers.Main) { game.play(0, 0) }
        awaitReady(game)
        withContext(Dispatchers.Main) { game.play(1, 0) }
        awaitReady(game)
        withContext(Dispatchers.Main) { game.undo() }
        assertEquals(0, awaitReady(game).lastMoveIndex)
        withContext(Dispatchers.Main) { game.pass() }
        awaitReady(game)
        withContext(Dispatchers.Main) { game.pass() }
        assertEquals(GoPhase.SCORING, awaitReady(game).phase)
        withContext(Dispatchers.Main) { game.confirm(GoPlayer.BLACK) }
        awaitReady(game)
        withContext(Dispatchers.Main) { game.confirm(GoPlayer.WHITE) }
        assertEquals(GoPhase.FINISHED, awaitReady(game).phase)
        val text = withContext(Dispatchers.Main) { game.exportSgf() }
        withContext(Dispatchers.Main) { game.importSgf(text) }
        val imported = awaitReady(game)
        assertEquals(GoPhase.SCORING, imported.phase)
        assertEquals(GameResult.ONGOING, imported.result)
        assertFalse(imported.blackConfirmed)
        assertFalse(imported.whiteConfirmed)
        assertEquals(0, imported.lastMoveIndex)
    }
}
