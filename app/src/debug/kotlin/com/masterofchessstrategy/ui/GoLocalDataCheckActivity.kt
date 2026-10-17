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
import java.util.UUID

/** Debug-only production NavHost with an owned DB AND isolated read-only legacy file paths. */
class GoLocalDataCheckActivity : ComponentActivity() {
    internal lateinit var ownedDatabaseName: String
        private set
    internal lateinit var ownedDatabase: MocsDatabase
        private set
    internal lateinit var ownedLegacyDirectory: File
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ownedDatabaseName = savedInstanceState?.getString(NAME_KEY) ?: "go-local-ui-${UUID.randomUUID()}.db"
        require(ownedDatabaseName.matches(Regex("^go-local-ui-[0-9a-f-]{36}\\.db$")))
        ownedDatabase = synchronized(databases) {
            databases.getOrPut(ownedDatabaseName) {
                Room.databaseBuilder(applicationContext, MocsDatabase::class.java, ownedDatabaseName).build()
            }
        }
        ownedLegacyDirectory = File(cacheDir, ownedDatabaseName.removeSuffix(".db") + "-legacy")
        check(ownedLegacyDirectory.isDirectory || ownedLegacyDirectory.mkdir())
        val isolated = object : ContextWrapper(applicationContext) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = ownedLegacyDirectory
            override fun getFileStreamPath(name: String): File {
                require(name.matches(Regex("[a-z0-9-]+\\.bin")))
                return File(ownedLegacyDirectory, name)
            }
        }
        val storage = GoLocalStorage(ownedDatabase, isolated)
        enableEdgeToEdge()
        setContent { MasterOfChessStrategyApp(ownedDatabase, storage) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NAME_KEY, ownedDatabaseName)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val NAME_KEY = "go-local-owned-db"
        private val databases = mutableMapOf<String, MocsDatabase>()
        internal fun closeOwnedDatabase(name: String) {
            synchronized(databases) { databases.remove(name) }?.close()
        }
    }
}
