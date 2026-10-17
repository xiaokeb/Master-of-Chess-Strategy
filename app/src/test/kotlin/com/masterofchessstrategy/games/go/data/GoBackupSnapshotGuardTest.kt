package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.*
import org.junit.Test

class GoBackupSnapshotGuardTest {
    @Test fun everyAdmissionCompletesBeforeTheFirstSnapshotGuardAndNoWriteOccurs() {
        val events = mutableListOf<String>()
        val first = Adapter("TEST_FIRST", GameType.GO, 0, events)
        val second = Adapter("TEST_SECOND", GameType.LANZHOU_SQUARE_CHESS, 1, events)
        val prepared = GameBackupRegistry(listOf(second, first)).prepareRestore(listOf(second.record(), first.record()), 5)
        assertEquals(2, prepared.size)
        assertEquals(listOf("prepare TEST_FIRST", "prepare TEST_SECOND", "guard TEST_SECOND:2", "guard TEST_FIRST:2"), events)
    }

    @Test fun guardFailurePreventsReturningAnyPreparedReplacement() {
        val events = mutableListOf<String>()
        val adapter = Adapter("TEST_REJECT", GameType.GO, 0, events).apply { rejectGuard = true }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(listOf(adapter)).prepareRestore(listOf(adapter.record()), 5) }
        assertEquals(listOf("prepare TEST_REJECT", "guard TEST_REJECT:1"), events)
    }

    @Test fun guardsCannotMutateTheFixedPreparedListOrItsRegistration() {
        val events = mutableListOf<String>()
        val adapter = Adapter("TEST_MUTATE", GameType.GO, 0, events)
        adapter.onGuard = { records ->
            assertThrows(UnsupportedOperationException::class.java) { (records as MutableList).clear() }
            adapter.descriptor = adapter.descriptor.copy(canonicalOrder = 3)
        }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(listOf(adapter)).prepareRestore(listOf(adapter.record()), 5) }
    }

    private class Adapter(kind: String, game: GameType, order: Int, private val events: MutableList<String>) :
        GameBackupAdapter, GameBackupSnapshotGuard {
        override var descriptor = GameBackupDescriptor(kind, 5, BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, game), 1, order, 1_000)
        var rejectGuard = false
        var onGuard: (List<PreparedGameBackup>) -> Unit = {}
        fun record() = GameBackupRecord(descriptor.kind, listOf(descriptor.storageSlot.gameType.code.toString()))
        override fun validate(record: GameBackupRecord, containerVersion: Int) { require(record == record()) }
        override suspend fun read(database: MocsDatabase) = emptyList<GameBackupRecord>()
        override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
            events += "prepare ${descriptor.kind}"
            return object : PreparedGameBackup {
                override val storageSlot = descriptor.storageSlot
                override suspend fun write(database: MocsDatabase) { error("No unit-test database writes") }
            }
        }
        override fun validatePreparedSnapshot(records: List<PreparedGameBackup>) {
            events += "guard ${descriptor.kind}:${records.size}"
            require(!rejectGuard)
            onGuard(records)
        }
    }
}
