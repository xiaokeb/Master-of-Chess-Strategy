package com.masterofchessstrategy.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.go.GoAiMatchFileCodec
import com.masterofchessstrategy.games.go.GoAiMatchViewModel
import com.masterofchessstrategy.games.go.GoAiMatchUiState
import com.masterofchessstrategy.games.go.data.GoLocalStorage
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

/** Debug-only process boundary for the production Go play route and an explicitly seeded QA database. */
class GoFormalProcessCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = requireNotNull(intent.getStringExtra("owned_database"))
        val matchId = requireNotNull(intent.getStringExtra("match_id"))
        require(name.matches(Regex("go-formal-process-qa-[0-9a-f-]{36}\\.db")))
        val token = name.removePrefix("go-formal-process-qa-").removeSuffix(".db")
        require(UUID.fromString(token).toString() == token)
        require(UUID.fromString(matchId).toString() == matchId)
        check(getDatabasePath(name).exists()) { "Seed the dedicated Go database before starting the process" }
        val db = Room.databaseBuilder(applicationContext, MocsDatabase::class.java, name).build()
        val legacy = File(cacheDir, name.removeSuffix(".db") + "-legacy")
        check(legacy.isDirectory || legacy.mkdir())
        val context = object : ContextWrapper(applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = legacy
            override fun getFileStreamPath(name: String): File {
                require(name.matches(Regex("[a-z0-9-]+\\.bin")))
                return File(legacy, name)
            }
        }
        val storage = GoLocalStorage(db, context)
        val writers = LocalDataWriterRegistry.forDatabase(db)
        setContent { MocsAppWindow { GoAiPlayNavigationRoute(storage, writers, matchId, ::finish) } }
        val directory = File(filesDir, "go-formal-process-qa")
        check(directory.isDirectory || directory.mkdir())
        val proof = AtomicFile(File(directory, name.removeSuffix(".db") + ".json"))
        lifecycleScope.launch {
            val repository = storage.formalAiRepository()
            var last: String? = null
            var inspected: GoAiMatchUiState? = null
            while (isActive) {
                val view = writers.snapshot().filterIsInstance<GoAiMatchViewModel>().singleOrNull()?.state?.value
                val match = view?.match
                if (view != null && !view.loadingModel && match != null && match !== inspected && !match.closed &&
                    !match.game.saving && !match.thinking && match.game.error == null) {
                    val saved = withContext(Dispatchers.IO) { repository.load() }
                    if (saved != null && saved.matchId == match.matchId && saved.config == match.config &&
                        saved.undoUsed == match.undoUsed && saved.hintUsed == match.hintUsed) {
                        val value = JSONObject().put("database", name).put("pid", Process.myPid())
                            .put("match_id", saved.matchId).put("phase", match.game.phase.name)
                            .put("event_count", match.eventCount).put("opening_event_count", match.openingEventCount)
                            .put("checkpoint_sha256", sha256(GoAiMatchFileCodec.encode(saved)))
                            .put("opening_sha256", saved.copyOpeningBytes()?.let(::sha256) ?: JSONObject.NULL)
                            .put("undo_remaining", saved.config.difficulty.undoLimit - saved.undoUsed)
                            .put("hint_remaining", saved.config.difficulty.hintLimit - saved.hintUsed)
                            .put("automatic", saved.config.automatic).put("automatic_used", saved.automaticUsed)
                            .put("auto_continue", saved.autoContinueEnabled).put("series_game", saved.autoContinueGameNumber)
                            .put("series_limit", saved.autoContinueMaxGames).put("completion_stored", match.completionStored)
                            .put("black_confirmed", match.game.blackConfirmed).put("white_confirmed", match.game.whiteConfirmed)
                            .toString()
                        if (value != last) {
                            withContext(Dispatchers.IO) {
                                val stream = proof.startWrite()
                                try {
                                    stream.write(value.toByteArray(Charsets.UTF_8))
                                    proof.finishWrite(stream)
                                } catch (failure: Throwable) {
                                    proof.failWrite(stream)
                                    throw failure
                                }
                            }
                            last = value
                        }
                        inspected = match
                    }
                }
                delay(250)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
