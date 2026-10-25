package com.masterofchessstrategy.ui

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Run after the host has killed the actual UI process; inspect the exact owned disk source. */
class FangqiProcessCheckpointInstrumentedTest {
    @Test fun inspectOrCleanTheHostOwnedCheckpoint() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val name = checkNotNull(args.getString(FangqiProcessCheckActivity.DATABASE_ARGUMENT))
        require(FangqiProcessCheckActivity.isOwnedDatabase(name))
        val context = instrumentation.targetContext
        val phase = checkNotNull(args.getString("phase"))
        if (phase == "cleanup") {
            assertTrue(context.deleteDatabase(name))
            val legacy = File(context.cacheDir, name.removeSuffix(".db") + "-legacy")
            assertTrue(legacy.listFiles()!!.isEmpty())
            assertTrue(legacy.delete())
            return@runBlocking
        }
        require(phase in listOf("first", "second"))
        val expected = if (phase == "first") 1 else 2
        val database = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        try {
            val row = checkNotNull(database.activeGameDao().find(4))
            assertEquals(0, row.modeCode)
            assertNull(row.difficultyCode)
            assertEquals(5, row.envelopeVersion)
            assertEquals(1, row.engineFormatVersion)
            assertEquals(expected.toString(), row.engineState.toString(Charsets.US_ASCII).split('|', limit = 6)[4])
            args.getString("expected_session_id")?.let { assertEquals(it, row.sessionId) }
            NativeLanzhouFangqiSession().use { native ->
                assertTrue(native.restore(row.engineState))
                assertArrayEquals(row.engineState, native.serialize())
                assertEquals(1, native.snapshot().board[4 * 7])
                assertEquals(0, native.snapshot().board[5 * 7])
                if (expected == 2) {
                    assertEquals(2, native.snapshot().board[2 * 7])
                    assertEquals(0, native.snapshot().board[7])
                }
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(row.engineState).joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2, '0')
            }
            val proof = JSONObject().put("database", name).put("phase", phase).put("sessionId", row.sessionId)
                .put("actions", expected).put("stateSha256", hash).put("updatedAt", row.updatedAtEpochMillis)
            val directory = File(context.filesDir, "fangqi-process-qa").apply { check(isDirectory || mkdir()) }
            File(directory, name.removeSuffix(".db") + "-$phase.json").writeText(proof.toString())
        } finally { database.close() }
    }
}
