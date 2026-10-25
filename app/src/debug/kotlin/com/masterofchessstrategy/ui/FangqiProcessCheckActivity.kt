package com.masterofchessstrategy.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.room.Room
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import java.io.File

/** ADB-only process-death fixture. The mandatory name can address only a dedicated QA database. */
class FangqiProcessCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = checkNotNull(intent.getStringExtra(DATABASE_ARGUMENT))
        require(isOwnedDatabase(name))
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
        val goStorage = GoLocalStorage(database, isolated)
        enableEdgeToEdge()
        setContent { MasterOfChessStrategyApp(database, goStorage) }
    }

    companion object {
        internal const val DATABASE_ARGUMENT = "owned_database"
        internal fun isOwnedDatabase(name: String) = name.matches(Regex("^fangqi-process-qa-[0-9a-f-]{36}\\.db$"))
        private val databases = mutableMapOf<String, MocsDatabase>()
    }
}
