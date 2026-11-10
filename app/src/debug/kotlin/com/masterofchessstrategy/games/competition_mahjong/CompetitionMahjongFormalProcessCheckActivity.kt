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
import com.masterofchessstrategy.games.competition_mahjong.match.play.CompetitionMahjongFormalRoute
import com.masterofchessstrategy.games.competition_mahjong.match.play.CompetitionMahjongMatchPlayState
import com.masterofchessstrategy.games.competition_mahjong.match.play.CompetitionMahjongMatchPlayViewModel
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomStorage
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

/** Debug-only process for the production table. It opens only a pre-seeded QA database. */
class CompetitionMahjongFormalProcessCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = requireNotNull(intent.getStringExtra("owned_database"))
        val prefix = "competition-mahjong-process-qa-"
        require(name.startsWith(prefix) && name.endsWith(".db"))
        val token = name.removePrefix(prefix).removeSuffix(".db")
        require(UUID.fromString(token).toString() == token)
        check(getDatabasePath(name).exists()) { "Seed the dedicated Mahjong database before launch" }

        val database = Room.databaseBuilder(applicationContext, MocsDatabase::class.java, name).build()
        val writers = LocalDataWriterRegistry.forDatabase(database)
        setContent { MocsAppWindow { CompetitionMahjongFormalRoute(database, 0, writers, ::finish) } }
        val directory = File(filesDir, "competition-mahjong-process-qa")
        check(directory.isDirectory || directory.mkdir())
        val receipt = AtomicFile(File(directory, "$token.json"))
        lifecycleScope.launch {
            var observed: CompetitionMahjongMatchPlayState? = null
            while (isActive) {
                val state = writers.snapshot().filterIsInstance<CompetitionMahjongMatchPlayViewModel>().singleOrNull()?.state?.value
                if (state != null && state !== observed && state.canOperate && state.error == null) {
                    val committed = withContext(Dispatchers.IO) {
                        database.withTransaction {
                            CompetitionMahjongFormalRoomStorage.readMctxInTransaction(
                                database, database.competitionMahjongFormalRoomDao(), 0,
                            )
                        }
                    }
                    val snapshot = committed?.let { CompetitionMahjongFormalSnapshotCodec.decode(it, 0) }
                    val active = snapshot?.active
                    val usage = active?.assistance
                    if (committed != null && active != null && usage != null && active.config.matchId == state.series.matchId &&
                        active.activeHandIndex == state.series.activeHandIndex &&
                        active.ledger.completedHandCount == state.series.completedHandCount && usage == state.assistance) {
                        val data = JSONObject().put("database", name).put("pid", Process.myPid())
                            .put("match_id", active.config.matchId).put("difficulty", active.config.botLevel)
                            .put("hand_index", active.activeHandIndex).put("settled_hands", active.ledger.completedHandCount)
                            .put("phase", state.series.table?.phase?.name ?: JSONObject.NULL)
                            .put("scores", (0..3).map { active.ledger.cumulativeScores[it] }.joinToString(","))
                            .put("hints_used", usage.hintUsed).put("undos_used", usage.undoUsed)
                            .put("hints_remaining", usage.hintRemaining ?: JSONObject.NULL)
                            .put("undos_remaining", usage.undoRemaining ?: JSONObject.NULL)
                            .put("checkpoint_sha256", sha256(active.engineRecord))
                            .put("snapshot_sha256", sha256(committed))
                        withContext(Dispatchers.IO) {
                            val stream = receipt.startWrite()
                            try {
                                stream.write(data.toString().toByteArray(Charsets.UTF_8))
                                receipt.finishWrite(stream)
                            } catch (failure: Throwable) {
                                receipt.failWrite(stream)
                                throw failure
                            }
                        }
                        observed = state
                    }
                }
                delay(250)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
