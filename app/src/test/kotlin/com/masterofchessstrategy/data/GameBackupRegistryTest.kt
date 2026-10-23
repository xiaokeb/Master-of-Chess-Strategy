package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.*
import org.junit.Test

class GameBackupRegistryTest {
    @Test fun crossOwnerGuardReceivesImmutableCoreOutcomesBeforeAnyWrite() {
        val outcome = MatchOutcome("one", GameType.GO, StoredGameMode.HUMAN_VS_AI,
            com.masterofchessstrategy.engine.Difficulty.EASY, 1,
            com.masterofchessstrategy.engine.GameResult.SECOND_PLAYER_WIN, 1)
        val caller = mutableListOf(outcome)
        var calls = 0
        val owner = object : Adapter(GameBackupDescriptor("TIGER_GOAT_ACTIVE", 5, tigerSlot, 1, 0, 1000)),
            GameBackupCoreSnapshotGuard {
            override fun validateCoreSnapshot(records: List<PreparedGameBackup>, outcomes: List<MatchOutcome>) {
                calls++
                caller.clear()
                assertEquals(listOf(outcome), outcomes)
                assertEquals(1, records.size)
                assertThrows(UnsupportedOperationException::class.java) { (outcomes as MutableList).clear() }
            }
        }
        GameBackupRegistry(listOf(owner)).prepareRestore(listOf(record()), 5, coreOutcomes = caller)
        assertEquals(1, calls)
        assertEquals(0, caller.size)
    }

    @Test fun duplicateCoreOutcomeIdentityIsRejectedBeforeGameAdmission() {
        val outcome = MatchOutcome("same", GameType.GO, StoredGameMode.HUMAN_VS_AI,
            com.masterofchessstrategy.engine.Difficulty.EASY, 1,
            com.masterofchessstrategy.engine.GameResult.SECOND_PLAYER_WIN, 1)
        val owner = adapter()
        assertThrows(IllegalArgumentException::class.java) {
            GameBackupRegistry(listOf(owner)).prepareRestore(listOf(record()), 5, coreOutcomes = listOf(outcome, outcome))
        }
        assertEquals(0, owner.admissionCalls)
    }

    private val tigerSlot = BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.TIGER_AND_GOAT)
    private val matchSlot = BackupStorageSlot(BackupStorageTable.COMPETITION_MATCH, GameType.STANDARD_MAHJONG)
    private fun record(kind: String = "TIGER_GOAT_ACTIVE", game: GameType = GameType.TIGER_AND_GOAT) =
        GameBackupRecord(kind, listOf(game.code.toString(), "owned"))

    private open class Adapter(
        override var descriptor: GameBackupDescriptor,
        private val preparedSlot: BackupStorageSlot = descriptor.storageSlot,
        private val failAdmission: Boolean = false,
    ) : GameBackupAdapter {
        var validationCalls = 0
        var admissionCalls = 0
        override fun validate(record: GameBackupRecord, containerVersion: Int) { validationCalls++ }
        override suspend fun read(database: MocsDatabase): List<GameBackupRecord> = error("Not used by unit tests")
        override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
            admissionCalls++
            require(!failAdmission) { "Rejected native history" }
            return object : PreparedGameBackup {
                override val storageSlot = preparedSlot
                override suspend fun write(database: MocsDatabase) = error("Not used by unit tests")
            }
        }
    }
    private fun adapter(kind: String = "TIGER_GOAT_ACTIVE", slot: BackupStorageSlot = tigerSlot,
        version: Int = 5, order: Int = 0, max: Int = 1, bytes: Int = 1_000) =
        Adapter(GameBackupDescriptor(kind, version, slot, max, order, bytes))

    @Test fun recordOwnsFieldsAndRejectsWireSeparatorsAndMalformedUtf8() {
        val fields = mutableListOf("5", "value")
        val value = GameBackupRecord("TIGER_GOAT_ACTIVE", fields)
        fields.clear()
        assertEquals(listOf("5", "value"), value.fields)
        assertThrows(UnsupportedOperationException::class.java) { (value.fields as MutableList<String>).clear() }
        for (bad in listOf("x|y", "x\ny", "x\ry", "x\u0000y", "\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { GameBackupRecord("TIGER_GOAT_ACTIVE", listOf(bad)) }
        }
    }

    @Test fun canonicalRecordsHaveValueEqualityAndAccurateByteCount() {
        val value = GameBackupRecord("EXAMPLE", listOf("5", "字符"))
        assertEquals(value, GameBackupRecord("EXAMPLE", listOf("5", "字符")))
        assertEquals("EXAMPLE|5|字符".toByteArray(Charsets.UTF_8).size, value.encodedSizeBytes)
        assertEquals(value.hashCode(), GameBackupRecord("EXAMPLE", listOf("5", "字符")).hashCode())
    }

    @Test fun reservedKindsAndCoreActiveSlotCannotBeClaimed() {
        for (kind in listOf("ACTIVE", "RECORD", "SETTINGS", "CREATED", "SHA256", "TUTORIAL", "OUTCOME", "SELECTION", "ENDGAME")) {
            assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(listOf(adapter(kind))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameBackupRegistry(listOf(adapter(slot = BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.CHINESE_CHESS))))
        }
    }

    @Test fun duplicateKindAndSlotRegistrationsFail() {
        assertThrows(IllegalArgumentException::class.java) {
            GameBackupRegistry(listOf(adapter(), adapter(slot = matchSlot)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameBackupRegistry(listOf(adapter(), adapter(kind = "SECOND_KIND")))
        }
    }

    @Test fun invalidDescriptorsFailBeforeDataIsRead() {
        for (version in listOf(0, 3, 6)) {
            assertThrows(IllegalArgumentException::class.java) { adapter(version = version) }
        }
        assertThrows(IllegalArgumentException::class.java) { adapter(order = -1) }
        assertThrows(IllegalArgumentException::class.java) { adapter(max = 0) }
        assertThrows(IllegalArgumentException::class.java) { adapter(bytes = 0) }
    }

    @Test fun registryOwnsItsRegistrationSnapshot() {
        val source = mutableListOf<GameBackupAdapter>(adapter())
        val registry = GameBackupRegistry(source)
        source.clear()
        registry.validate(listOf(record()), 5)
    }

    @Test fun registerOrderDoesNotChangeCanonicalWireOrder() {
        val tiger = adapter()
        val match = adapter("COMPETITION_MATCH", matchSlot, 4, 1)
        val values = listOf(record("COMPETITION_MATCH", GameType.STANDARD_MAHJONG), record())
        val expected = listOf("TIGER_GOAT_ACTIVE", "COMPETITION_MATCH")
        assertEquals(expected, GameBackupRegistry(listOf(match, tiger)).canonicalRecords(values, 5).map { it.kind })
        assertEquals(expected, GameBackupRegistry(listOf(tiger, match)).canonicalRecords(values, 5).map { it.kind })
    }

    @Test fun missingAdapterAndWrongVersionAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).validate(listOf(record()), 5) }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(listOf(adapter())).validate(listOf(record()), 4) }
    }

    @Test fun wrongGameOwnershipAndExcessRecordsAreRejected() {
        val registry = GameBackupRegistry(listOf(adapter()))
        assertThrows(IllegalArgumentException::class.java) { registry.validate(listOf(record(game = GameType.CHINESE_CHESS)), 5) }
        assertThrows(IllegalArgumentException::class.java) { registry.validate(listOf(record(), record()), 5) }
        assertThrows(IllegalArgumentException::class.java) { registry.validate(listOf(GameBackupRecord("TIGER_GOAT_ACTIVE", listOf("05"))), 5) }
    }

    @Test fun adapterByteLimitAndInvalidKindAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            GameBackupRegistry(listOf(adapter(bytes = 4))).validate(listOf(record()), 5)
        }
        for (kind in listOf("", "x", "A|B", "A\nB")) {
            assertThrows(IllegalArgumentException::class.java) { GameBackupRecord(kind, listOf("5")) }
        }
    }

    @Test fun envelopeValidationDoesNotRunNativeAdmission() {
        val owner = adapter()
        GameBackupRegistry(listOf(owner)).validate(listOf(record()), 5)
        assertEquals(1, owner.validationCalls)
        assertEquals(0, owner.admissionCalls)
    }

    @Test fun prepareRunsNativeAdmissionAndRejectsForeignPreparedSlots() {
        val owner = adapter()
        val prepared = GameBackupRegistry(listOf(owner)).prepareRestore(listOf(record()), 5)
        assertEquals(tigerSlot, prepared.single().storageSlot)
        assertEquals(1, owner.admissionCalls)
        val wrong = Adapter(owner.descriptor, matchSlot)
        assertThrows(IllegalArgumentException::class.java) {
            GameBackupRegistry(listOf(wrong)).prepareRestore(listOf(record()), 5)
        }
    }

    @Test fun nativeFailureStopsPreparationWithoutInvokingWrites() {
        val owner = Adapter(adapter().descriptor, failAdmission = true)
        assertThrows(IllegalArgumentException::class.java) {
            GameBackupRegistry(listOf(owner)).prepareRestore(listOf(record()), 5)
        }
        assertEquals(1, owner.admissionCalls)
    }

    @Test fun unknownNonEmptyTargetSlotsAreNotSilentlyIgnored() {
        val registry = GameBackupRegistry(listOf(adapter()))
        val chinese = BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.CHINESE_CHESS)
        registry.requireSupportedSlots(setOf(chinese, tigerSlot))
        assertThrows(IllegalArgumentException::class.java) { registry.requireSupportedSlots(setOf(matchSlot)) }
    }

    @Test fun inventoryChecksActualRowsIncludingUnknownCodesAndWrongMatchTableOwners() {
        assertEquals(setOf(tigerSlot, matchSlot), BackupStorageInventory.fromCodes(
            listOf(GameType.TIGER_AND_GOAT.code), listOf(GameType.STANDARD_MAHJONG.code)))
        assertThrows(IllegalArgumentException::class.java) { BackupStorageInventory.fromCodes(listOf(999), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { BackupStorageInventory.fromCodes(emptyList(), listOf(GameType.TIGER_AND_GOAT.code)) }
        assertThrows(IllegalArgumentException::class.java) { BackupStorageInventory.fromCodes(listOf(5, 5), emptyList()) }
    }

    @Test fun emptyExtensionsKeepV4AndTigerRequiresV5() {
        val registry = GameBackupRegistry(listOf(adapter()))
        assertEquals(4, registry.containerVersion(emptyList()))
        assertEquals(5, registry.containerVersion(listOf(record())))
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).containerVersion(listOf(record())) }
    }

    @Test fun aggregateByteLimitCountsEveryRecordAndCanonicalNewline() {
        val tiger = record()
        val match = record("COMPETITION_MATCH", GameType.STANDARD_MAHJONG)
        val owners = listOf(adapter(), adapter("COMPETITION_MATCH", matchSlot, 4, 1))
        val exact = tiger.encodedSizeBytes + match.encodedSizeBytes + 2
        GameBackupRegistry(owners, exact).validate(listOf(tiger, match), 5)
        assertThrows(IllegalArgumentException::class.java) {
            GameBackupRegistry(owners, exact - 1).validate(listOf(tiger, match), 5)
        }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(owners, 0) }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(owners, GameBackupRecord.MAX_BYTES + 1) }
    }

    @Test fun changedRegistrationFailsEvenForAnEmptyInputOrInventory() {
        val owner = adapter()
        val registry = GameBackupRegistry(listOf(owner))
        owner.descriptor = owner.descriptor.copy(maximumRecords = 2)
        assertThrows(IllegalArgumentException::class.java) { registry.validate(emptyList(), 4) }
        assertThrows(IllegalArgumentException::class.java) { registry.containerVersion(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { registry.requireSupportedSlots(emptySet()) }
    }

    @Test fun legacySpellingsAreNormalizedBeforeCanonicalOwnershipValidation() {
        val owner = object : Adapter(adapter().descriptor) {
            override fun normalize(record: GameBackupRecord, containerVersion: Int): GameBackupRecord {
                require(record.fields.first().toIntOrNull() == descriptor.storageSlot.gameType.code)
                return GameBackupRecord(record.kind, listOf(descriptor.storageSlot.gameType.code.toString()) + record.fields.drop(1))
            }
        }
        val registry = GameBackupRegistry(listOf(owner))
        assertEquals(record(), registry.normalizeRecord(GameBackupRecord("TIGER_GOAT_ACTIVE", listOf("+05", "owned")), 5))
        assertEquals(0, owner.admissionCalls)
        assertThrows(IllegalArgumentException::class.java) { registry.normalizeRecord(record(), 4) }
    }

    @Test fun normalizationCannotSwitchToAnotherRegisteredKindOrGame() {
        val wrong = object : Adapter(adapter().descriptor) {
            override fun normalize(record: GameBackupRecord, containerVersion: Int) =
                GameBackupRecord("COMPETITION_MATCH", listOf(GameType.STANDARD_MAHJONG.code.toString()))
        }
        val registry = GameBackupRegistry(listOf(wrong, adapter("COMPETITION_MATCH", matchSlot, 4, 1)))
        assertThrows(IllegalArgumentException::class.java) { registry.normalizeRecord(record(), 5) }
    }

    @Test fun descriptorChangedInsideValidationIsRejectedImmediately() {
        val owner = object : Adapter(adapter().descriptor) {
            override fun validate(record: GameBackupRecord, containerVersion: Int) {
                descriptor = descriptor.copy(maximumRecords = 2)
            }
        }
        val registry = GameBackupRegistry(listOf(owner))
        assertThrows(IllegalArgumentException::class.java) { registry.validate(listOf(record()), 5) }
    }
}
