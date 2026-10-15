package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Framework-only fake extensions: this does not stand in for Native admission. */
class RegisteredBackupCodecTest {
    private class Codec(kind: String, game: GameType, version: Int, order: Int) : GameBackupAdapter {
        override val descriptor = GameBackupDescriptor(kind, version,
            BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, game), 1, order, 1_000)
        var admissionCalls = 0
        override fun validate(record: GameBackupRecord, containerVersion: Int) {
            require(record.fields.size == 2 && record.fields[1] == "payload")
        }
        override fun normalize(record: GameBackupRecord, containerVersion: Int) = GameBackupRecord(record.kind,
            listOf(requireNotNull(record.fields[0].toIntOrNull()).toString(), record.fields[1]))
        override suspend fun read(database: MocsDatabase): List<GameBackupRecord> = error("Not used")
        override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup { admissionCalls++; error("Codec must not admit") }
    }
    private val tiger = Codec("TIGER_GOAT_ACTIVE", GameType.TIGER_AND_GOAT, 5, 0)
    private val other = Codec("OTHER_GAME", GameType.LANZHOU_SQUARE_CHESS, 4, 1)
    private val registry get() = GameBackupRegistry(listOf(other, tiger))
    private fun record(codec: Codec) = GameBackupRecord(codec.descriptor.kind,
        listOf(codec.descriptor.storageSlot.gameType.code.toString(), "payload"))

    @Test fun coreOnlyBackupIsV4AndDoesNotNeedAnyGameClass() {
        val source = LocalDataSnapshot(9)
        val empty = GameBackupRegistry(emptyList())
        val bytes = LocalDataBackupCodec.encode(source, empty)
        assertTrue(bytes.toString(Charsets.UTF_8).startsWith("MOCS-BACKUP|4\n"))
        assertEquals(source, LocalDataBackupCodec.decode(bytes, empty))
    }

    @Test fun extensionOrderingAndV5AreIndependentOfRegistrationOrder() {
        val source = LocalDataSnapshot(9, gameExtensions = listOf(record(other), record(tiger)))
        val encoded = LocalDataBackupCodec.encode(source, registry)
        val reverse = GameBackupRegistry(listOf(tiger, other))
        assertArrayEquals(encoded, LocalDataBackupCodec.encode(source, reverse))
        val lines = encoded.toString(Charsets.UTF_8).lines()
        assertEquals("MOCS-BACKUP|5", lines[0])
        assertTrue(lines[2].startsWith("TIGER_GOAT_ACTIVE|"))
        assertTrue(lines[3].startsWith("OTHER_GAME|"))
        val restored = LocalDataBackupCodec.decode(encoded, reverse)
        assertEquals(listOf(record(tiger), record(other)), restored.gameExtensions)
        assertArrayEquals(encoded, LocalDataBackupCodec.encode(restored, registry))
        assertEquals(0, tiger.admissionCalls)
        assertEquals(0, other.admissionCalls)
    }

    @Test fun missingExtensionCannotBeDecodedOrEncodedSilently() {
        val source = LocalDataSnapshot(9, gameExtensions = listOf(record(tiger)))
        val bytes = LocalDataBackupCodec.encode(source, registry)
        val empty = GameBackupRegistry(emptyList())
        assertThrows(IllegalArgumentException::class.java) { LocalDataBackupCodec.decode(bytes, empty) }
        assertThrows(IllegalArgumentException::class.java) { LocalDataBackupCodec.encode(source, empty) }
    }

    @Test fun acceptedLegacyNumericSpellingsAreNormalizedBeforeValidation() {
        val bytes = signed("MOCS-BACKUP|5\nCREATED|9\nTIGER_GOAT_ACTIVE|+05|payload\n")
        val restored = LocalDataBackupCodec.decode(bytes, registry)
        assertEquals(listOf(record(tiger)), restored.gameExtensions)
        assertTrue(LocalDataBackupCodec.encode(restored, registry).toString(Charsets.UTF_8)
            .contains("TIGER_GOAT_ACTIVE|5|payload\n"))
    }

    @Test fun outerVersionAndDuplicateExtensionLimitsAreStillEnforced() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.decode(signed("MOCS-BACKUP|4\nCREATED|9\nTIGER_GOAT_ACTIVE|5|payload\n"), registry)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.decode(signed("MOCS-BACKUP|5\nCREATED|9\nTIGER_GOAT_ACTIVE|5|payload\nTIGER_GOAT_ACTIVE|5|payload\n"), registry)
        }
    }

    @Test fun legacyVersionsWithoutExtensionsRemainReadable() {
        for (version in 1..5) {
            assertEquals(LocalDataSnapshot(9), LocalDataBackupCodec.decode(
                signed("MOCS-BACKUP|$version\nCREATED|9\n"), GameBackupRegistry(emptyList())))
        }
    }

    @Test fun finalBudgetIncludesHeadersCreatedAndChecksumNotOnlyExtensions() {
        val source = LocalDataSnapshot(9, gameExtensions = listOf(record(tiger)))
        val expected = LocalDataBackupCodec.encode(source, registry)
        assertArrayEquals(expected, LocalDataBackupCodec.encode(source, registry, expected.size))
        assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.encode(source, registry, expected.size - 1)
        }
    }

    @Test fun checksumAndCanonicalLineEndingValidationRemainStrict() {
        val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(9), GameBackupRegistry(emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.decode(bytes.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }, registry)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.decode(bytes.toString(Charsets.UTF_8).replace("\n", "\r\n").toByteArray(), registry)
        }
    }

    private fun signed(body: String): ByteArray {
        val checksum = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return (body + "SHA256|$checksum\n").toByteArray(Charsets.UTF_8)
    }
}
