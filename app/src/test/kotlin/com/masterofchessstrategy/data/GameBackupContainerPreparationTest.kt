package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.*
import org.junit.Test

/** Admission only: writes deliberately fail if these unit tests accidentally invoke installation. */
class GameBackupContainerPreparationTest {
    private val formal = BackupStorageSlot(BackupStorageTable.COMPETITION_FORMAL, GameType.STANDARD_MAHJONG)
    private val learning = BackupStorageSlot(BackupStorageTable.COMPETITION_LEARNING, GameType.STANDARD_MAHJONG)
    private fun record(kind: String, value: String = "valid") = GameBackupRecord(kind, listOf("2", value))

    private open inner class Adapter(override var descriptor: GameBackupDescriptor) : GameBackupAdapter {
        var individualCalls = 0
        override fun validate(record: GameBackupRecord, containerVersion: Int) { require(record.fields[1] == "valid") }
        override suspend fun read(database: MocsDatabase): List<GameBackupRecord> = error("No Room in this test")
        override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
            individualCalls++
            return prepared(descriptor.storageSlot)
        }
    }
    private fun prepared(slot: BackupStorageSlot, ids: Set<String> = emptySet()) = object : PreparedGameBackup {
        override val storageSlot = slot
        override val completedRecordIds = ids
        override suspend fun write(database: MocsDatabase) = error("Admission must not install")
    }
    private inner class Container(
        val action: (List<GameBackupRecord>) -> PreparedGameBackup = { prepared(formal) },
    ) : Adapter(GameBackupDescriptor("FORMAL", 5, formal, 4, 1, 1000)), GameBackupContainerPreparation {
        var calls = 0
        override fun prepareContainer(records: List<GameBackupRecord>, context: GameBackupPreparationContext): PreparedGameBackup {
            calls++
            return action(records)
        }
    }

    @Test fun fourRowsAreAdmittedOnceWithImmutableCandidateCompanions() {
        val caller = mutableListOf(record("LEARNING"), record("FORMAL"), record("FORMAL"), record("FORMAL"), record("FORMAL"))
        val owner = Container { candidate ->
            caller.clear()
            assertEquals(5, candidate.size)
            assertEquals(4, candidate.count { it.kind == "FORMAL" })
            assertEquals(1, candidate.count { it.kind == "LEARNING" })
            assertThrows(UnsupportedOperationException::class.java) { (candidate as MutableList).clear() }
            prepared(formal)
        }
        val other = Adapter(GameBackupDescriptor("LEARNING", 5, learning, 1, 2, 1000))
        assertEquals(2, GameBackupRegistry(listOf(owner, other)).prepareRestore(caller, 5).size)
        assertEquals(1, owner.calls)
        assertEquals(0, owner.individualCalls)
        assertEquals(1, other.individualCalls)
    }

    @Test fun missingOwnerStillPreparesAnExplicitReplacementFact() {
        val owner = Container { assertTrue(it.isEmpty()); prepared(formal) }
        assertEquals(formal, GameBackupRegistry(listOf(owner)).prepareRestore(emptyList(), 1).single().storageSlot)
        assertEquals(1, owner.calls)
    }

    @Test fun malformedOrUnavailableWireRejectsBeforeAnyAdmission() {
        val owner = Container()
        val registry = GameBackupRegistry(listOf(owner))
        assertThrows(IllegalArgumentException::class.java) { registry.prepareRestore(listOf(record("FORMAL", "bad")), 5) }
        assertThrows(IllegalArgumentException::class.java) { registry.prepareRestore(listOf(record("UNKNOWN")), 5) }
        assertThrows(IllegalArgumentException::class.java) { registry.prepareRestore(List(5) { record("FORMAL") }, 5) }
        assertEquals(0, owner.calls)
    }

    @Test fun foreignSlotAndMutatedRegistrationAreRejected() {
        val foreign = Container { prepared(learning) }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(listOf(foreign)).prepareRestore(emptyList(), 5) }
        lateinit var changed: Container
        changed = Container { changed.descriptor = changed.descriptor.copy(maximumRecords = 3); prepared(formal) }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(listOf(changed)).prepareRestore(emptyList(), 5) }
    }

    @Test fun containerFactsParticipateInExistingCrossOwnerIdentityGuards() {
        val owner = Container { prepared(formal, setOf("same-record")) }
        assertThrows(IllegalArgumentException::class.java) {
            GameBackupRegistry(listOf(owner)).prepareRestore(emptyList(), 5, coreRecordIds = setOf("same-record"))
        }
        val guard = object : Adapter(GameBackupDescriptor("LEARNING", 5, learning, 1, 2, 1000)), GameBackupSnapshotGuard {
            override fun validatePreparedSnapshot(records: List<PreparedGameBackup>) {
                assertEquals(setOf(formal), records.map { it.storageSlot }.toSet())
                throw IllegalArgumentException("Cross-owner rejection")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(listOf(Container(), guard)).prepareRestore(emptyList(), 5) }
    }

    @Test fun aFailedContainerNeverFallsBackToIndividualAdmission() {
        val owner = Container { throw IllegalArgumentException("Rejected fourth seat") }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(listOf(owner)).prepareRestore(listOf(record("FORMAL")), 5) }
        assertEquals(1, owner.calls)
        assertEquals(0, owner.individualCalls)
    }
}
