package com.masterofchessstrategy.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.room.Room
import androidx.lifecycle.lifecycleScope
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiGameViewModel
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiSessionAdmission
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** ADB-only separate process/task: the real NavHost can open only an already seeded UUID QA DB. */
class FangqiFormalProcessCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = checkNotNull(intent.getStringExtra(DATABASE_ARGUMENT))
        require(isOwnedDatabase(name))
        check(getDatabasePath(name).exists()) { "Seed the dedicated QA database before opening it" }
        val database = synchronized(databases) {
            databases.getOrPut(name) { Room.databaseBuilder(applicationContext, MocsDatabase::class.java, name).build() }
        }
        val directory = File(cacheDir, name.removeSuffix(".db") + "-legacy")
        check(directory.isDirectory || directory.mkdir())
        val isolated = object : ContextWrapper(applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = directory
            override fun getFileStreamPath(name: String): File {
                require(name.matches(Regex("[a-z0-9-]+\\.bin")))
                return File(directory, name)
            }
        }
        enableEdgeToEdge()
        val storage = GoLocalStorage(database, isolated)
        setContent { MasterOfChessStrategyApp(database, storage) }
        lifecycleScope.launch {
            val deadline = SystemClock.uptimeMillis() + 120_000
            val proofs = File(filesDir, "fangqi-formal-native-qa").apply { check(isDirectory || mkdir()) }
            val output = AtomicFile(File(proofs, name.removeSuffix(".db") + ".json"))
            var last = ""
            while (isActive && SystemClock.uptimeMillis() < deadline) {
                val game = LocalDataWriterRegistry.forDatabase(database).snapshot().filterIsInstance<FangqiGameViewModel>()
                    .singleOrNull { !it.state.value.retired && !it.retirementCompletion.isCompleted }
                val page = game?.state?.value
                val interaction = page?.interaction?.state?.value
                val native = page?.session
                if (page != null && !page.loading && native != null && interaction != null &&
                    !interaction.aiThinking && !interaction.savePending) {
                    // Read Native on main between serialized UI actions; publish only owned immutable bytes.
                    val bytes = native.serialize()
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        (it.toInt() and 255).toString(16).padStart(2, '0')
                    }
                    val value = JSONObject().put("database", name).put("pid", Process.myPid())
                        .put("actions", FangqiSessionAdmission.inspectHistory(bytes)).put("native_sha256", hash)
                        .put("result", interaction.snapshot.result.name).put("save_failed", interaction.saveFailed)
                        .put("current_player", interaction.snapshot.currentPlayer.name)
                        .put("pending_captures", interaction.snapshot.pendingCaptures)
                        .put("formal", !page.restartAllowed).put("undo_allowed", page.undoAllowed)
                        .put("owner", System.identityHashCode(game)).toString()
                    if (value != last) {
                        withContext(Dispatchers.IO) {
                            val stream = output.startWrite()
                            try { stream.write(value.toByteArray(Charsets.UTF_8)); output.finishWrite(stream) }
                            catch (failure: Throwable) { output.failWrite(stream); throw failure }
                        }
                        last = value
                    }
                }
                delay(100)
            }
        }
    }

    companion object {
        internal const val DATABASE_ARGUMENT = "owned_database"
        internal fun isOwnedDatabase(name: String) = name.matches(Regex("^fangqi-formal-process-qa-[0-9a-f-]{36}\\.db$"))
        private val databases = mutableMapOf<String, MocsDatabase>()
    }
}
