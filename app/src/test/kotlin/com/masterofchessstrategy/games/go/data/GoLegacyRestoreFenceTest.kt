package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.GoLegacyImportScopes
import com.masterofchessstrategy.data.LocalDataMigrationDao
import com.masterofchessstrategy.data.LocalDataMigrationEntity
import com.masterofchessstrategy.data.sealGoLegacySourcesForRestore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** DAO seam tests policy; actual rollback is covered separately by the Room instrumented test. */
class GoLegacyRestoreFenceTest {
    @Test fun emptyInstallationSealsEveryFixedSourceWithoutNeedingAnAdapter() = runTest {
        val dao = Markers()
        dao.sealGoLegacySourcesForRestore()
        assertEquals(GoLegacyImportScopes.ALL.toSet(), dao.rows.keys)
        assertTrue(dao.rows.values.all { it.handledVersion == 1 })
        assertEquals(3, dao.insertCalls)
    }

    @Test fun repeatedRestorePreservesHigherVersionsAndUnrelatedMarkers() = runTest {
        val dao = Markers()
        val future = LocalDataMigrationEntity(GoLegacyImportScopes.LEARNING, 7)
        val unrelated = LocalDataMigrationEntity("other-game-owned-scope", 9)
        dao.rows[future.scope] = future
        dao.rows[unrelated.scope] = unrelated
        dao.sealGoLegacySourcesForRestore()
        val once = dao.rows.toMap()
        dao.sealGoLegacySourcesForRestore()
        assertEquals(once, dao.rows)
        assertEquals(future, dao.rows[future.scope])
        assertEquals(unrelated, dao.rows[unrelated.scope])
    }

    @Test fun corruptExistingMarkerIsRejectedInsteadOfDowngradedOrIgnored() = runTest {
        val dao = Markers()
        val bad = LocalDataMigrationEntity(GoLegacyImportScopes.LEARNING, 0)
        dao.rows[bad.scope] = bad
        try { dao.sealGoLegacySourcesForRestore(); fail("must reject corrupt fence") }
        catch (error: IllegalStateException) { assertEquals("Invalid Go legacy restore fence", error.message) }
        assertEquals(bad, dao.rows[bad.scope])
        assertFalse(dao.rows.containsKey(GoLegacyImportScopes.ENDGAME))
    }

    @Test fun failureToPersistAMarkerIsNotReportedAsSuccessfulSealing() = runTest {
        val dao = Markers().apply { dropInserts = true }
        try { dao.sealGoLegacySourcesForRestore(); fail("missing readback must fail") }
        catch (error: IllegalStateException) { assertEquals("Invalid Go legacy restore fence", error.message) }
        assertTrue(dao.rows.isEmpty())
    }

    private class Markers : LocalDataMigrationDao {
        val rows = linkedMapOf<String, LocalDataMigrationEntity>()
        var insertCalls = 0
        var dropInserts = false
        override suspend fun find(scope: String) = rows[scope]
        override suspend fun insert(entity: LocalDataMigrationEntity): Long {
            insertCalls++
            if (dropInserts || rows.containsKey(entity.scope)) return -1
            rows[entity.scope] = entity
            return rows.size.toLong()
        }
    }
}
