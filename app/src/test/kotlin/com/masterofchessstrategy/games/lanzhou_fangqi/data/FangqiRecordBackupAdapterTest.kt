package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Actual codec/registry with explicit replay doubles; no Native or database win is claimed. */
class FangqiRecordBackupAdapterTest {
    @Test fun thirteenFieldsPreserveAllRecordMetadataAndPrintableId() {
        val value = record().copy(recordId = "A|~", difficulty = Difficulty.HARD,
            result = GameResult.SECOND_PLAYER_WIN, isFavorite = true, mode = StoredGameMode.ENDGAME,
            isEndgame = true, completedAtEpochMillis = Long.MAX_VALUE, playerIndex = 0)
        val adapter = adapter(GameResult.SECOND_PLAYER_WIN)
        val wire = adapter.encode(value)
        assertEquals(13, wire.fields.size)
        assertEquals("417c7e", wire.fields[2])
        assertEquals(5, adapter.descriptor.minimumContainerVersion)
        assertEquals(34, adapter.descriptor.canonicalOrder)
        val prepared = adapter.prepareRestore(wire)
        assertEquals(value.toGameRecordEntity(), prepared.record.toGameRecordEntity())
        assertEquals(setOf("A|~"), prepared.completedRecordIds)
        val secondPlayer = value.copy(mode = StoredGameMode.HUMAN_VS_AI, isEndgame = false,
            difficulty = null, playerIndex = 1)
        assertEquals(secondPlayer.toGameRecordEntity(),
            adapter.prepareRestore(adapter.encode(secondPlayer)).record.toGameRecordEntity())
    }

    @Test fun structuralValidationDoesNotReplayButFormatOnePreparationDoes() {
        var calls = 0
        val adapter = FangqiRecordBackupAdapter { input -> calls++; replay(input) }
        val wire = adapter.encode(record())
        adapter.validate(wire, 5)
        assertEquals(0, calls)
        adapter.prepareRestore(wire)
        assertEquals(1, calls)
    }

    @Test fun nonTerminalMismatchedNonCanonicalPendingOrRefusedNativeResultsAreRejected() {
        val wire = adapter().encode(record())
        for (actual in listOf(GameResult.ONGOING, GameResult.SECOND_PLAYER_WIN)) {
            rejected { adapter(actual).prepareRestore(wire) }
        }
        rejected { FangqiRecordBackupAdapter { input -> FangqiRankedReplayResult(input, GameResult.FIRST_PLAYER_WIN, 1) }.prepareRestore(wire) }
        rejected { FangqiRecordBackupAdapter { FangqiRankedReplayResult(root(), GameResult.FIRST_PLAYER_WIN, 0) }.prepareRestore(wire) }
        rejected { FangqiRecordBackupAdapter { throw IOException("explicit native refusal") }.prepareRestore(wire) }
    }

    @Test fun opaqueLegacyVersionTwoNeverReplaysOrCreatesRankedSource() {
        var calls = 0
        val recordAdapter = FangqiRecordBackupAdapter { input -> calls++; replay(input) }
        val legacy = record().copy(engineFormatVersion = 2, engineState = byteArrayOf(0, -1), moveCount = 10_000)
        val wire = recordAdapter.encode(legacy)
        recordAdapter.validate(wire, 5)
        val prepared = registry(recordAdapter).prepareRestore(listOf(wire), 5).single() as PreparedFangqiRecord
        assertEquals(legacy.toGameRecordEntity(), prepared.record.toGameRecordEntity())
        val verified = validator().validate(FangqiRankedStorageSnapshot(emptyList(), emptyList(), null,
            emptyList(), listOf(prepared.record.toGameRecordEntity())))
        assertEquals(0, calls)
        assertTrue(verified.creations.isEmpty())
        assertTrue(verified.wins.winsByDifficulty.values.all { it == 0 })
    }

    @Test fun unicodeAndDelimiterIdsRoundTripInBothRecordFormats() {
        for (version in listOf(1, 2)) {
            var calls = 0
            val recordAdapter = FangqiRecordBackupAdapter { input -> calls++; replay(input) }
            for (id in listOf("旧棋谱|红胜", " 对局 A|~ ", "棋".repeat(64), "\uD83D\uDE00".repeat(32))) {
                val value = record().copy(recordId = id, engineFormatVersion = version)
                val wire = recordAdapter.encode(value)
                assertEquals(hex(id.toByteArray(Charsets.UTF_8)), wire.fields[2])
                val restored = registry(recordAdapter).prepareRestore(listOf(wire), 5).single() as PreparedFangqiRecord
                assertEquals(value.toGameRecordEntity(), restored.record.toGameRecordEntity())
                assertEquals(setOf(id), restored.completedRecordIds)
            }
            assertEquals(if (version == 1) 4 else 0, calls)
        }
    }

    @Test fun recordIdsRejectMalformedUtf8AndUnpairedUtf16InsteadOfReplacingCharacters() {
        val wire = adapter().encode(record())
        for (bad in listOf("80", "c0af", "e4b8", "eda080", "f4908080", "ff")) {
            rejected { adapter().validate(wire.with(2, bad), 5) }
        }
        for (bad in listOf("\uD800", "\uDC00", "对局\uD800尾")) {
            rejected { adapter().encode(record().copy(recordId = bad)) }
        }
    }

    @Test fun recordIdsRetainNonBlankAndCharacterLimitsWithCanonicalBoundedHex() {
        val wire = adapter().encode(record())
        for (bad in listOf("", "20", "e38080", hex("棋".repeat(65).toByteArray(Charsets.UTF_8)),
            hex("\uD83D\uDE00".repeat(33).toByteArray(Charsets.UTF_8)), "61".repeat(257), "e6a38b".uppercase())) {
            rejected { adapter().validate(wire.with(2, bad), 5) }
        }
        rejected { adapter().encode(record().copy(recordId = "棋".repeat(65))) }
    }

    @Test fun longFormatOneHistoriesPreserveEveryActionAndByte() {
        for (count in listOf(10_001, 16_384)) {
            val value = record().copy(engineState = history(count).bytes, moveCount = count)
            val prepared = adapter().prepareRestore(adapter().encode(value))
            assertEquals(count, prepared.record.moveCount)
            assertArrayEquals(value.engineState, prepared.record.engineState)
        }
        val wire = adapter().encode(record())
        rejected { adapter().validate(wire.with(7, hex(history(16_385).bytes)).with(8, "16385"), 5) }
    }

    @Test fun unknownContainerWireVersionsEnumsAndFieldCountsAreRejected() {
        val wire = adapter().encode(record())
        rejected { adapter().validate(wire, 4) }
        for (bad in listOf(wire.with(0, "3"), wire.with(1, "2"), wire.with(3, "99"), wire.with(4, "99"),
            wire.with(5, "0"), wire.with(5, "4"), wire.with(6, "0"), wire.with(6, "3"),
            GameBackupRecord(wire.kind, wire.fields.dropLast(1)), GameBackupRecord(wire.kind, wire.fields + "extra"))) {
            rejected { adapter().validate(bad, 5) }
        }
    }

    @Test fun numericAndBitFieldsRequireExactCanonicalSpellings() {
        val wire = adapter().encode(record())
        for (bad in listOf("", "00", "01", "+1", "-1", " 1", "1 ", "1.0", "9223372036854775808")) {
            rejected { adapter().validate(wire.with(11, bad), 5) }
        }
        for (bad in listOf("", "01", "2", "true", "-")) {
            rejected { adapter().validate(wire.with(9, bad), 5) }
            rejected { adapter().validate(wire.with(10, bad), 5) }
        }
        rejected { adapter().validate(wire.with(8, "2147483648"), 5) }
        rejected { adapter().validate(wire.with(12, "2"), 5) }
    }

    @Test fun lowercaseHexIdBoundAndPayloadSizeAreEnforcedForBothFormats() {
        val wire = adapter().encode(record())
        for (bad in listOf("", "0", "gg", wire.fields[7].uppercase())) rejected { adapter().validate(wire.with(7, bad), 5) }
        for (bad in listOf("20", "ff", "aa".repeat(65))) rejected { adapter().validate(wire.with(2, bad), 5) }
        rejected { adapter().validate(wire.with(7, "61".repeat(1_048_577)), 5) }
        val legacyWire = adapter().encode(record().copy(engineFormatVersion = 2))
        rejected { adapter().validate(legacyWire.with(7, "61".repeat(65_537)), 5) }
    }

    @Test fun preparedRecordOwnsItsBytesAndReturnedCopies() {
        val original = record()
        val prepared = adapter().prepareRestore(adapter().encode(original))
        val expected = original.engineState.copyOf()
        original.engineState.fill(0)
        prepared.record.engineState.fill(0)
        assertArrayEquals(expected, prepared.record.engineState)
    }

    @Test fun recordCannotBeOwnedByBothCoreAndTheFangqiExtension() {
        val wire = adapter().encode(record())
        rejected { registry().prepareRestore(listOf(wire), 5, setOf(ID)) }
        rejected { registry().prepareRestore(listOf(wire), 5, setOf(ID), emptyList(), listOf(record())) }
        rejected { registry().prepareRestore(listOf(wire, wire), 5) }
    }

    @Test fun duplicateRecordOwnershipAcrossIndependentExtensionSlotsIsRejected() {
        val other = object : GameBackupAdapter {
            override val descriptor = GameBackupDescriptor("OTHER_RECORD_OWNER", 5,
                BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.TIGER_AND_GOAT), 1, 90, 100)
            override suspend fun read(database: MocsDatabase) = emptyList<GameBackupRecord>()
            override fun validate(record: GameBackupRecord, containerVersion: Int) = Unit
            override fun prepareRestore(record: GameBackupRecord) = object : PreparedGameBackup {
                override val storageSlot = descriptor.storageSlot
                override val completedRecordIds = setOf(ID)
                override suspend fun write(database: MocsDatabase) = error("No DB writes in JVM guard tests")
            }
        }
        val registry = GameBackupRegistry(listOf(adapter(), other))
        rejected { registry.prepareRestore(listOf(adapter().encode(record()), GameBackupRecord("OTHER_RECORD_OWNER", listOf("5"))), 5) }
    }

    @Test fun preparedRecordCompletesExactRankedCompanionsWithoutCoreRecordDuplication() {
        val wires = rankedWires() + adapter().encode(record())
        val prepared = registry().prepareRestore(wires, 5, emptySet(), listOf(outcome()), emptyList())
        assertEquals(3, prepared.size)
        assertTrue(prepared.last() is PreparedFangqiRecord)
        val wrong = adapter().encode(record().copy(completedAtEpochMillis = 31))
        rejected { registry().prepareRestore(rankedWires() + wrong, 5, emptySet(), listOf(outcome())) }
        rejected { registry().prepareRestore(rankedWires(), 5, emptySet(), listOf(outcome())) }
    }

    @Test fun explicitDeletedRankedRecordCannotReappearThroughPreparedRecordExtension() {
        val deleted = settlement().copy(recordDeletedAtEpochMillis = 40)
        val wires = listOf(FangqiRankedBackupWire.encodeStart(start()), FangqiRankedBackupWire.encodeSettlement(deleted))
        registry().prepareRestore(wires, 5, emptySet(), listOf(outcome()))
        rejected { registry().prepareRestore(wires + adapter().encode(record()), 5, emptySet(), listOf(outcome())) }
    }

    private fun adapter(result: GameResult = GameResult.FIRST_PLAYER_WIN) = FangqiRecordBackupAdapter { input -> FangqiRankedReplayResult(input, result, 0) }
    private fun validator() = FangqiRankedSnapshotValidator(FangqiRankedLedger(::replay), FangqiSessionAdmission { it })
    private fun registry(recordAdapter: FangqiRecordBackupAdapter = adapter()) = GameBackupRegistry(listOf(
        FangqiRankedStartBackupAdapter(validator()), FangqiRankedSettlementBackupAdapter(), recordAdapter))
    private fun replay(input: FangqiRankedHistory) = FangqiRankedReplayResult(input,
        if (input == root()) GameResult.ONGOING else GameResult.FIRST_PLAYER_WIN, 0)
    private fun root() = FangqiRankedLedger.standardInitialHistory()
    private fun history(count: Int) = FangqiRankedHistory((root().bytes.toString(Charsets.US_ASCII).removeSuffix("|0|") +
        "|$count|" + List(count) { "m,1,35,28" }.joinToString(";")).toByteArray(Charsets.US_ASCII))
    private fun creation() = FangqiRankedCreation(1, FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME,
        FangqiRankedPosition(FangqiRankedIdentity(ID, GameType.LANZHOU_SQUARE_CHESS, FangqiStoredMode.HUMAN_VS_AI,
            Difficulty.EASY, 0, FangqiRankedLedger.RULES_VERSION, 1), root(), 10, 0, 0, 0))
    private fun receipt() = FangqiRankedTerminalReceipt(1, creation().sha256, creation().initial.copy(history = history(1),
        updatedAtEpochMillis = 20, acceptedActionCount = 1), GameResult.FIRST_PLAYER_WIN, 30)
    private fun start() = FangqiRankedRowMapping.encodeStartUnvalidated(creation())
    private fun settlement() = FangqiRankedRowMapping.encodeSettlementUnvalidated(creation(), receipt(), null)
    private fun rankedWires() = listOf(FangqiRankedBackupWire.encodeStart(start()), FangqiRankedBackupWire.encodeSettlement(settlement()))
    private fun outcome() = MatchOutcome(ID, GameType.LANZHOU_SQUARE_CHESS, StoredGameMode.HUMAN_VS_AI, Difficulty.EASY,0,GameResult.FIRST_PLAYER_WIN,30)
    private fun record() = GameRecord(ID,GameType.LANZHOU_SQUARE_CHESS,StoredGameMode.HUMAN_VS_AI,Difficulty.EASY,
        GameResult.FIRST_PLAYER_WIN,history(1).bytes,1,completedAtEpochMillis=30,engineFormatVersion=1)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun GameBackupRecord.with(index: Int, value: String) = GameBackupRecord(kind, fields.mapIndexed { i, s -> if(i == index)value else s })
    private fun rejected(action: () -> Unit) { try { action(); fail("Invalid Fangqi record backup accepted") }
        catch (_: IllegalArgumentException) { } catch (_: IOException) { } }
    companion object { private const val ID = "ranked-record-backup" }
}
