package com.masterofchessstrategy.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.room.Room
import com.masterofchessstrategy.data.MocsDatabase
import java.util.UUID

/** Debug-only host: the production NavHost always receives this test's own Room instance. */
class TigerGoatNavigationCheckActivity : ComponentActivity() {
    internal lateinit var ownedDatabase: MocsDatabase
        private set
    internal lateinit var ownedDatabaseName: String
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ownedDatabaseName = savedInstanceState?.getString(NAME_KEY) ?: "tiger-navigation-${UUID.randomUUID()}.db"
        require(ownedDatabaseName.matches(Regex("^tiger-navigation-[0-9a-f-]{36}\\.db$")))
        ownedDatabase = synchronized(databases) {
            databases.getOrPut(ownedDatabaseName) {
                Room.databaseBuilder(applicationContext, MocsDatabase::class.java, ownedDatabaseName).build()
            }
        }
        enableEdgeToEdge()
        setContent { MasterOfChessStrategyApp(ownedDatabase) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(NAME_KEY, ownedDatabaseName)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val NAME_KEY = "tiger-navigation-owned-db"
        private val databases = mutableMapOf<String, MocsDatabase>()
        internal fun closeOwnedDatabase(name: String) {
            synchronized(databases) { databases.remove(name) }?.close()
        }
    }
}
