package com.masterofchessstrategy.games.lanzhou_mahjong.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.createProductionGameBackupRepository
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongAssistanceUsage
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongDecisionPoint
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*

/** A unique disk database and empty legacy directory, never the application's default database. */
internal class LanzhouMahjongOwnedDatabaseFixture {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val token = UUID.randomUUID().toString()
    private val databaseName = "lzmj-data-qa-$token.db"
    private val legacyDirectory = File(context.cacheDir, "lzmj-backup-qa-$token").apply { check(mkdir()) }
    private val isolated = object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = legacyDirectory
        override fun getFileStreamPath(name: String): File {
            require(name.matches(Regex("[a-z0-9-]+\\.bin")))
            return File(legacyDirectory, name)
        }
    }

    var db: MocsDatabase
        private set

    init {
        require(databaseName.matches(Regex("lzmj-data-qa-[0-9a-f-]{36}\\.db")))
        check(!context.getDatabasePath(databaseName).exists())
        db = open()
    }

    private fun open(): MocsDatabase = Room.databaseBuilder(context, MocsDatabase::class.java, databaseName).build()

    fun store() = RoomLanzhouMahjongAdmittedSessionStore(db, db.activeGameDao())

    fun repository(): LocalDataBackupRepository = createProductionGameBackupRepository(db,
        ChineseChessEndgamePackParser.loadBundled(context.assets), GoLocalStorage(db, isolated),
        FangqiLocalStorage.forDatabase(db)) { bytes ->
        NativeChineseChessEngine().use { it.restore(bytes) == RestoreResult.Restored }
    }

    suspend fun row(): ActiveGameEntity? = db.activeGameDao().find(3)

    fun reopen() {
        db.close()
        check(context.getDatabasePath(databaseName).exists())
        db = open()
    }

    fun close() {
        db.close()
        // Delete only the exact UUID name created above, including its Room sidecars.
        if (context.getDatabasePath(databaseName).exists()) check(context.deleteDatabase(databaseName))
        check(legacyDirectory.listFiles()?.isEmpty() == true) { "Owned Go legacy fixture unexpectedly gained files" }
        check(legacyDirectory.delete())
    }
}

internal fun withOwnedLanzhouDatabase(block: suspend (LanzhouMahjongOwnedDatabaseFixture) -> Unit) = runBlocking {
    val fixture = LanzhouMahjongOwnedDatabaseFixture()
    try { block(fixture) } finally { fixture.close() }
}

/** Histories are produced by real Native actions; only the deliberately invalid fixture fabricates an action. */
internal object LanzhouMahjongNativeFixtures {
    const val SEED = 321L
    const val BASE = 2L

    fun initial(rule: Int = 2, seed: Long = SEED, base: Long = BASE): ByteArray =
        NativeLanzhouMahjongSession(seed, 0, base, 0).use { native ->
            val initial = native.serialize()
            assertEquals(2, initial[4].toInt()) // The actual new-session JNI entry must use rule v2.
            require(rule in 1..2)
            if (rule == 1) {
                // Construct a valid legacy zero-action envelope, as in the existing Native rule test.
                // All later history is generated after real restore has fixed the legacy rule.
                initial[4] = 1
                checksum(initial)
            }
            assertEquals(RestoreResult.Restored, native.restore(initial))
            assertArrayEquals(initial, native.serialize())
            initial
        }

    fun snapshot(rule: Int = 2, discard: Boolean = true, usage: Int = 2,
        timestamp: Long = 123, decisions: Boolean = true): LanzhouMahjongStoredSession {
        val bytes = NativeLanzhouMahjongSession(SEED, 0, BASE, 0).use { native ->
            assertEquals(RestoreResult.Restored, native.restore(initial(rule)))
            if (discard) {
                val action = native.legalActions().first { it.kind == LanzhouMahjongActionKind.DISCARD }
                assertEquals(ActionResult.Accepted, native.apply(action))
            }
            native.serialize().also { saved ->
                assertEquals(RestoreResult.Restored, native.restore(saved))
                assertArrayEquals(saved, native.serialize())
            }
        }
        return LanzhouMahjongStoredSession(LanzhouMahjongSessionRecord(Difficulty.MEDIUM, 0, SEED,
            0, BASE, SEED, bytes, LanzhouMahjongAssistanceUsage(Difficulty.MEDIUM, usage, usage),
            if (discard && decisions) listOf(LanzhouMahjongDecisionPoint(0, SEED)) else emptyList()), timestamp)
    }

    fun legacyOuter(row: ActiveGameEntity, record: LanzhouMahjongSessionRecord): ActiveGameEntity {
        require(record.assistance.undoUsed == 0 && record.assistance.hintUsed == 0 && record.decisions.isEmpty())
        val legacy = LanzhouMahjongSessionRecordCodec.encode(record).copyOf(40 + record.nativeState.size + 4)
        legacy[4] = 1
        ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN).putInt(legacy.size - 4,
            CRC32().apply { update(legacy, 0, legacy.size - 4) }.value.toInt())
        return row.copy(engineFormatVersion = 1, engineState = legacy)
    }

    fun illegalDrawSnapshot(timestamp: Long = 456): LanzhouMahjongStoredSession {
        val initial = initial()
        val illegal = ByteArray(initial.size + 3)
        initial.copyInto(illegal, endIndex = 126)
        ByteBuffer.wrap(illegal).order(ByteOrder.LITTLE_ENDIAN).putInt(122, 1)
        illegal[126] = LanzhouMahjongActionKind.DRAW.code.toByte()
        illegal[127] = 0
        illegal[128] = 255.toByte() // Dealer is in DISCARD phase; this DRAW is illegal.
        checksum(illegal)
        NativeLanzhouMahjongSession(SEED, 0, BASE, 0).use { native ->
            val before = native.serialize()
            assertTrue(native.restore(illegal) is RestoreResult.Rejected)
            assertArrayEquals(before, native.serialize())
        }
        return LanzhouMahjongStoredSession(LanzhouMahjongSessionRecord(Difficulty.MEDIUM, 0, SEED,
            0, BASE, SEED, illegal), timestamp)
    }

    private fun checksum(bytes: ByteArray) {
        var sum = 0x811c9dc5.toInt()
        for (index in 0 until bytes.size - 4) sum = (sum xor (bytes[index].toInt() and 255)) * 16777619
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 4, sum)
    }
}
