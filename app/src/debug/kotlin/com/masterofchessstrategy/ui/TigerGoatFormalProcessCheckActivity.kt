package com.masterofchessstrategy.ui

import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.games.tiger_goat.NativeTigerGoatRulesSession
import com.masterofchessstrategy.games.tiger_goat.TigerGoatTutorialProgress
import com.masterofchessstrategy.games.tiger_goat.TigerGoatTutorialStep
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalPageState
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalViewModel
import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatFormalActiveRow
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

/** Debug-only evidence host. The release route and Native engine run against a UUID-owned database. */
class TigerGoatFormalProcessCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = requireNotNull(intent.getStringExtra("qa_token"))
        require(UUID.fromString(token).toString() == token)
        val databaseName = "tiger-goat-formal-qa-$token.db"
        val database = Room.databaseBuilder(applicationContext, MocsDatabase::class.java, databaseName).build()
        val writers = LocalDataWriterRegistry.forDatabase(database)
        val directory = File(filesDir, "tiger-goat-formal-qa")
        check(directory.isDirectory || directory.mkdir())
        val proof = AtomicFile(File(directory, "$token.json"))
        lifecycleScope.launch {
            val progress = TigerGoatTutorialProgress(database)
            for (step in progress.load() until TigerGoatTutorialStep.entries.size) progress.complete(step)
            setContent {
                MocsAppWindow {
                    TigerGoatNavigationRoute(database, this@TigerGoatFormalProcessCheckActivity, writers, ::finish)
                }
            }
            var inspected: TigerGoatFormalPageState? = null
            while (isActive) {
                val page = writers.snapshot().filterIsInstance<TigerGoatFormalViewModel>().singleOrNull()?.uiState?.value
                if (page != null && page !== inspected && !page.busy && !page.thinking && page.error == null && !page.retired) {
                    val text = withContext(Dispatchers.Default) { checkpoint(database, page, databaseName) }
                    if (text != null) {
                        val stream = proof.startWrite()
                        try {
                            stream.write(text.toByteArray(Charsets.UTF_8))
                            proof.finishWrite(stream)
                        } catch (failure: Exception) {
                            proof.failWrite(stream)
                            throw failure
                        }
                        inspected = page
                    }
                }
                delay(80)
            }
        }
    }

    private suspend fun checkpoint(db: MocsDatabase, page: TigerGoatFormalPageState, name: String): String? {
        val game = page.game ?: return null
        val row = db.activeGameDao().find(GameType.TIGER_AND_GOAT.code) ?: return null
        val saved = TigerGoatFormalActiveRow.from(row)
        if (page.config != saved.config) return null
        val history = saved.copyBytes()
        NativeTigerGoatRulesSession(NativeTigerGoatSession()).use { rules ->
            check(rules.restore(history) && rules.actionCount == saved.actionCount)
            val committed = rules.snapshot()
            if (committed.pieces != game.pieces || committed.toMove != game.toMove ||
                committed.winner != game.winner || committed.phase != game.phase) return null
        }
        return JSONObject().put("pid", Process.myPid()).put("database", name)
            .put("session_id", saved.sessionId).put("revision", saved.revision)
            .put("human_side", saved.config.humanSide).put("difficulty", saved.config.difficulty.name)
            .put("action_count", saved.actionCount).put("phase", game.phase.name)
            .put("winner", game.winner.name).put("to_move", game.toMove).put("reserve", game.reserve)
            .put("board_sha256", digest(game.pieces.map(Int::toByte).toByteArray()))
            .put("checkpoint_sha256", digest(history)).toString()
    }

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
