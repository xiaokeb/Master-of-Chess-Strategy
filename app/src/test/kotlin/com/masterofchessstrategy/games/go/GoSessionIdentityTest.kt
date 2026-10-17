package com.masterofchessstrategy.games.go

import org.junit.Assert.*
import org.junit.Test

class GoSessionIdentityTest {
    @Test fun newGamesAndImportsHaveDistinctStableIdsAndExplicitSources() {
        val local = GoSessionIdentity.create(GoSessionOrigin.LOCAL)
        val imported = GoSessionIdentity.create(GoSessionOrigin.SGF_IMPORT)
        assertNotEquals(local.sessionId, imported.sessionId)
        assertEquals(GoSessionOrigin.LOCAL, local.origin)
        assertEquals(GoSessionOrigin.SGF_IMPORT, imported.origin)
        assertEquals(64, local.recordId.length)
        assertEquals(local.recordId, local.copy().recordId)
    }

    @Test fun legacyIdentityIsDeterministicAndDoesNotInventAProvenance() {
        val bytes = byteArrayOf(1, 2, 3)
        val identity = GoSessionIdentity.fromLegacy(bytes)
        assertEquals(identity, GoSessionIdentity.fromLegacy(bytes.copyOf()))
        assertNotEquals(identity, GoSessionIdentity.fromLegacy(byteArrayOf(1, 2, 4)))
        assertEquals(GoSessionOrigin.LEGACY_UNKNOWN, identity.origin)
        assertEquals(0, identity.archiveRevision)
    }

    @Test fun branchChangesOnlyRecordIdentityAndNeverWraps() {
        val original = GoSessionIdentity.create(GoSessionOrigin.SGF_IMPORT)
        val branch = original.afterTerminalUndo()
        assertEquals(original.sessionId, branch.sessionId)
        assertEquals(original.origin, branch.origin)
        assertEquals(1, branch.archiveRevision)
        assertNotEquals(original.recordId, branch.recordId)
        assertTrue(original.copy(archiveRevision = Int.MAX_VALUE).recordId.length <= 64)
        assertThrows(IllegalStateException::class.java) {
            original.copy(archiveRevision = Int.MAX_VALUE).afterTerminalUndo()
        }
    }

    @Test fun invalidIdsAndNegativeRevisionsAreRejected() {
        for (id in listOf("", " ", "a:b", "路径", "x".repeat(81))) {
            assertThrows(IllegalArgumentException::class.java) {
                GoSessionIdentity(id, GoSessionOrigin.LOCAL)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GoSessionIdentity("ok", GoSessionOrigin.LOCAL, -1)
        }
    }
}
