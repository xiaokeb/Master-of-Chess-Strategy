package com.masterofchessstrategy.games.go.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class GoDetailedProgressMigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun fourteenToFifteenKeepsExistingGoCheckpointAndPermanentFences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "go-progress-migration-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 14).use { db ->
                // Raw SQL fixture checks byte preservation, not Native checkpoint legality.
                db.execSQL("INSERT INTO go_local_sessions VALUES (1,3,X'070809',99)")
                db.execSQL("INSERT INTO local_data_migrations VALUES ('go-legacy-local-session',1)")
                db.execSQL("INSERT INTO local_data_migrations VALUES ('go-legacy-learning-preferences',2)")
            }
            helper.runMigrationsAndValidate(name, 15, true, MocsDatabase.MIGRATION_14_15).use { db ->
                db.query("SELECT hex(checkpoint),updatedAtEpochMillis FROM go_local_sessions WHERE gameTypeCode=1").use {
                    assertTrue(it.moveToFirst()); assertEquals("070809", it.getString(0)); assertEquals(99L, it.getLong(1))
                }
                db.query("SELECT handledVersion FROM local_data_migrations WHERE scope='go-legacy-learning-preferences'").use {
                    assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM local_data_migrations").use {
                    assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM go_progress").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        } finally { assertTrue(context.deleteDatabase(name)) }
    }
}
