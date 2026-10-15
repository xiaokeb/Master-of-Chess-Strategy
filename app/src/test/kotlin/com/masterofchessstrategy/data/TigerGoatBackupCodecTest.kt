package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatBackupAdapter
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Pure envelope/codec tests; Native replay admission is tested separately on Android. */
class TigerGoatBackupCodecTest {
    private val tiger = TigerGoatBackupAdapter { error("Codec tests must not run Native admission") }
    private val registry = GameBackupRegistry(listOf(tiger))
    @Test fun archiveOwnsBytesAndEntityConversionDoesNotShareArrays() {
        val original = opening()
        val archive = TigerGoatActiveArchive("tiger-save", original, 0, 8)
        original.fill(0)
        archive.engineState.fill(0)
        val entity = archive.toEntity()
        assertEquals(5, entity.gameTypeCode)
        assertEquals(5, entity.envelopeVersion)
        assertEquals(2, entity.engineFormatVersion)
        assertEquals(archive, TigerGoatActiveArchive.fromEntity(entity))
        entity.engineState.fill(0)
        assertArrayEquals(opening(), archive.engineState)
    }

    @Test fun v1AndV2ArchivesRoundTripWithoutRewritingNativeBytes() {
        for (version in 1..2) {
            val archive = TigerGoatActiveArchive("session-$version", opening(version), 0, 12)
            val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(20,
                gameExtensions = listOf(tiger.encodeArchive(archive))), registry)
            assertTrue(bytes.toString(Charsets.UTF_8).startsWith("MOCS-BACKUP|5\n"))
            val restored = LocalDataBackupCodec.decode(bytes, registry)
            assertEquals(listOf(archive), restored.gameExtensions.map(tiger::decodeRecord))
            assertTrue(restored.activeSessions.isEmpty())
            assertArrayEquals(bytes, LocalDataBackupCodec.encode(restored, registry))
        }
    }

    @Test fun oldBackupWithoutTigerKeepsV4AndV1ThroughV4RemainReadable() {
        val encoded = LocalDataBackupCodec.encode(LocalDataSnapshot(20), registry)
        assertTrue(encoded.toString(Charsets.UTF_8).startsWith("MOCS-BACKUP|4\n"))
        val body = encoded.toString(Charsets.UTF_8).substringBefore("SHA256|")
        for (version in 1..4) {
            val decoded = LocalDataBackupCodec.decode(withChecksum(body.replace("MOCS-BACKUP|4", "MOCS-BACKUP|$version")), registry)
            assertTrue(decoded.gameExtensions.isEmpty())
            assertEquals(20L, decoded.createdAtEpochMillis)
        }
    }

    @Test fun invalidRowMetadataOrUnknownNativeVersionCannotBeNormalizedAway() {
        val row = TigerGoatActiveArchive("save", opening(), 0, 8).toEntity()
        for (bad in listOf(row.copy(gameTypeCode = 0), row.copy(modeCode = 1),
            row.copy(difficultyCode = 0), row.copy(envelopeVersion = 4), row.copy(engineFormatVersion = 1),
            row.copy(acceptedMoveCount = 1), row.copy(undoUseCount = 1), row.copy(hintUseCount = 1),
            row.copy(resultOverrideCode = 1), row.copy(timeControlMinutes = 1), row.copy(redRemainingMillis = 1),
            row.copy(blackRemainingMillis = 1), row.copy(turnStartedAtEpochMillis = 1),
            row.copy(pendingDrawOfferSideCode = 0), row.copy(autoPlayPaused = true),
            row.copy(autoPlaySpeedPermille = 999), row.copy(completedAutoGames = 1),
            row.copy(sessionVariantId = "extra"), row.copy(playerIndex = 1))) {
            assertThrows(IllegalArgumentException::class.java) { TigerGoatActiveArchive.fromEntity(bad) }
        }
        for (version in listOf(0, 3, 255)) {
            val bytes = opening().also { it[4] = version.toByte(); nativeChecksum(it) }
            assertThrows(IllegalArgumentException::class.java) { TigerGoatActiveArchive("save", bytes, 0, 1) }
        }
    }

    @Test fun nativeEnvelopeSizeCountChecksumAndIdentityAreBounded() {
        for (bad in listOf(ByteArray(52), ByteArray(196_659), opening().also { it[0] = 0 },
            opening().also { it[5] = 2; nativeChecksum(it) }, opening().also { it[47] = 1; nativeChecksum(it) },
            opening().also { it[10] = 3 })) {
            assertThrows(IllegalArgumentException::class.java) { TigerGoatActiveArchive("save", bad, 0, 1) }
        }
        for (id in listOf("", " ", "x".repeat(65), 0xd800.toChar().toString())) {
            assertThrows(IllegalArgumentException::class.java) { TigerGoatActiveArchive(id, opening(), 0, 1) }
        }
        assertThrows(IllegalArgumentException::class.java) { TigerGoatActiveArchive("save", opening(), 0, -1) }
    }

    @Test fun duplicateTigerOldHeaderAndTamperedRecordFieldsAreRejected() {
        val archive = TigerGoatActiveArchive("save", opening(), 0, 1)
        assertThrows(IllegalArgumentException::class.java) {
            LocalDataBackupCodec.encode(LocalDataSnapshot(1,
                gameExtensions = listOf(tiger.encodeArchive(archive), tiger.encodeArchive(archive))), registry)
        }
        val body = LocalDataBackupCodec.encode(LocalDataSnapshot(1,
            gameExtensions = listOf(tiger.encodeArchive(archive))), registry)
            .toString(Charsets.UTF_8).substringBefore("SHA256|")
        val line = body.lineSequence().first { it.startsWith("TIGER_GOAT_ACTIVE|") }
        val fields = line.split('|')
        val changed = listOf(
            body.replace("MOCS-BACKUP|5", "MOCS-BACKUP|4"),
            body + line + "\n",
            body.replace(line, fields.toMutableList().also { it[1] = GameType.CHINESE_CHESS.code.toString() }.joinToString("|")),
            body.replace(line, fields.toMutableList().also { it[2] = "2" }.joinToString("|")),
            body.replace(line, fields.toMutableList().also { it[3] = "1" }.joinToString("|")),
            body.replace(line, fields.toMutableList().also { it[5] = "1" }.joinToString("|")),
        )
        changed.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { LocalDataBackupCodec.decode(withChecksum(invalid), registry) }
        }
    }

    companion object {
        internal fun opening(version: Int = 2): ByteArray = ByteArray(53).also { bytes ->
            byteArrayOf(77, 79, 67, 84, version.toByte(), 1, 0, 1, 14, 0).copyInto(bytes)
            bytes[20] = 1; bytes[24] = 1
            for (point in listOf(6, 7, 8, 11, 13, 16, 17, 18)) bytes[10 + point] = 2
            nativeChecksum(bytes)
        }
        internal fun nativeChecksum(bytes: ByteArray) {
            var hash = 0x811c9dc5.toInt()
            for (index in 0 until bytes.size - 4) hash = (hash xor (bytes[index].toInt() and 255)) * 16777619
            repeat(4) { bytes[bytes.size - 4 + it] = (hash ushr (8 * it)).toByte() }
        }
        private fun withChecksum(body: String): ByteArray {
            val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
            return (body + "SHA256|$hash\n").toByteArray()
        }
    }
}
