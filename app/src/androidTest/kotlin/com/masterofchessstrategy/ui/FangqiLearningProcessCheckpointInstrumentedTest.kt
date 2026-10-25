package com.masterofchessstrategy.ui

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Inspect the host-owned disk database only after the actual QA UI process has died. */
class FangqiLearningProcessCheckpointInstrumentedTest {
    @Test fun inspectDurableLearningAfterActualProcessDeath() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val name = checkNotNull(args.getString(FangqiProcessCheckActivity.DATABASE_ARGUMENT))
        require(FangqiProcessCheckActivity.isOwnedDatabase(name))
        val phase = checkNotNull(args.getString("phase"))
        require(phase in listOf("first", "second"))
        val context = instrumentation.targetContext
        val database = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        try {
            val rows = database.fangqiLearningProgressDao().readAll()
            val keys = setOf("lesson:opening", "practice:lfq-practice-square-01") +
                if (phase == "second") setOf("lesson:step") else emptySet()
            assertEquals(keys, rows.map { it.contentKey }.toSet())
            assertEquals(keys.size, rows.size)
            assertTrue(rows.all { it.contentVersion == 1 && it.completedAtEpochMillis >= 0 })
            assertNull("Learning must not create an ordinary local game", database.activeGameDao().find(4))
            assertTrue("Learning cannot issue match rewards", database.matchOutcomeDao().listAll().isEmpty())
            val stable = rows.filter { it.contentKey != "lesson:step" }.sortedBy { it.contentKey }
                .joinToString("\n", postfix = "\n") { "${it.contentKey}|${it.contentVersion}|${it.completedAtEpochMillis}" }
            val hash = MessageDigest.getInstance("SHA-256").digest(stable.toByteArray(Charsets.UTF_8))
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            args.getString("expected_stable_sha")?.let { assertEquals(it, hash) }
            val values = JSONArray()
            rows.forEach { values.put(JSONObject().put("key", it.contentKey).put("version", it.contentVersion)
                .put("firstCompletedAt", it.completedAtEpochMillis)) }
            val proof = JSONObject().put("database", name).put("phase", phase).put("stableSha256", hash).put("rows", values)
            val directory = File(context.filesDir, "fangqi-learning-process-qa").apply { check(isDirectory || mkdir()) }
            File(directory, name.removeSuffix(".db") + "-$phase.json").writeText(proof.toString())
        } finally { database.close() }
    }
}
