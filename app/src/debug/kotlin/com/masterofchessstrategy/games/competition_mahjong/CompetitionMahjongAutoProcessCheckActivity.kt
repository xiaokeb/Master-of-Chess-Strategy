package com.masterofchessstrategy.games.competition_mahjong

import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.room.withTransaction
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoPlayViewModel
import com.masterofchessstrategy.games.competition_mahjong.auto.production.CompetitionMahjongAutoProductionRoute
import com.masterofchessstrategy.games.competition_mahjong.auto.storage.CompetitionMahjongAutoRow
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.ui.theme.MocsAppWindow
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Debug process for the production auto table. Only opens a UUID-owned, pre-seeded Room database. */
class CompetitionMahjongAutoProcessCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = requireNotNull(intent.getStringExtra("token"))
        require(UUID.fromString(token).toString() == token)
        val databaseName = "competition-auto-process-$token.db"
        check(getDatabasePath(databaseName).isFile)
        val database = Room.databaseBuilder(applicationContext, MocsDatabase::class.java, databaseName).build()
        val writers = LocalDataWriterRegistry.forDatabase(database)
        setContent {
            MocsAppWindow {
                CompetitionMahjongAutoProductionRoute(database, 0, archiveOnly = false, writers,
                    onLearning = {}, onBack = ::finish)
            }
        }
        val directory = File(filesDir, "competition-auto-process")
        check(directory.isDirectory || directory.mkdir())
        val receipt = AtomicFile(File(directory, "$token.json"))
        lifecycleScope.launch {
            var observed: String? = null
            while (isActive) {
                val state = writers.snapshot().filterIsInstance<CompetitionMahjongAutoPlayViewModel>()
                    .singleOrNull()?.state?.value?.playback
                val current = state?.activity
                if (state != null && current?.frame != null && !current.needsSave && !state.busy && state.error == null) {
                    val data = withContext(Dispatchers.IO) {
                        database.withTransaction {
                            val dao = database.competitionMahjongAutoDao()
                            val row = dao.find(0, CompetitionMahjongAutoRow.ACTIVE_KEY) ?: return@withTransaction null
                            val checkpoint = row.checkpoint()
                            if (checkpoint.config.runId != current.runId || checkpoint.seriesIndex != current.seriesIndex ||
                                checkpoint.activeHandIndex != current.handIndex ||
                                checkpoint.ledger.completedHandCount != current.completedHands ||
                                checkpoint.eventsAfterOpening != current.eventsAfterOpening ||
                                checkpoint.settings != current.settings || checkpoint.ledger.cumulativeScores != current.scores) {
                                return@withTransaction null
                            }
                            checkpoint.validateFrame(current.frame)
                            val archives = dao.archiveKeys(0)
                            val archive = dao.find(0, "${checkpoint.config.runId}/${checkpoint.seriesIndex}")
                            JSONObject().put("token", token).put("pid", Process.myPid())
                                .put("run_id", checkpoint.config.runId).put("series", current.seriesIndex)
                                .put("hand", current.handIndex).put("completed", current.completedHands)
                                .put("events", current.eventsAfterOpening).put("paused", current.settings.paused)
                                .put("finished", current.finished).put("phase", current.frame.phase.name)
                                .put("scores", (0..3).joinToString(",") { current.scores[it].toString() })
                                .put("checkpoint_sha256", sha(row.payload)).put("engine_sha256", sha(checkpoint.engineRecord))
                                .put("archive_count", archives.size).put("archive_sha256", archive?.let { sha(it.payload) })
                                .put("human_outcome_count", database.matchOutcomeDao().listAll().size)
                                .toString()
                        }
                    }
                    if (data != null && data != observed) {
                        withContext(Dispatchers.IO) { receipt.write { it.write(data.toByteArray(Charsets.UTF_8)) } }
                        observed = data
                    }
                }
                delay(150)
            }
        }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private inline fun AtomicFile.write(block: (java.io.FileOutputStream) -> Unit) {
        val stream = startWrite()
        try {
            block(stream)
            finishWrite(stream)
        } catch (failure: Throwable) {
            failWrite(stream)
            throw failure
        }
    }
}
