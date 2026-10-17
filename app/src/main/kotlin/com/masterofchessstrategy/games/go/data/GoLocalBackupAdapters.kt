package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.games.go.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Only these local-data slots are covered; GAI1 and ranked AI ledgers are separate contracts. */
internal interface GoLocalBackupAdapter : GameBackupAdapter, GameBackupSnapshotGuard {
    override fun validatePreparedSnapshot(records: List<PreparedGameBackup>) {
        val active = records.filterIsInstance<PreparedGoLocalBackup>()
        val archives = records.filterIsInstance<PreparedGoRecordBackup>()
        require(active.size <= 1 && archives.map { it.recordId }.distinct().size == archives.size) {
            "Duplicate Go checkpoint or completed record"
        }
        require(records.count { it is PreparedGoProgressBackup } <= 1) { "Duplicate Go progress bundle" }
        active.singleOrNull()?.let { checkpoint ->
            val session = checkpoint.admitted.session()
            val archived = archives.singleOrNull { it.recordId == session.identity.recordId }
            if (checkpoint.admitted.phase == GoPhase.FINISHED) {
                requireNotNull(archived) { "Go terminal checkpoint has no matching archive" }
                val record = archived.record()
                require(archived.source == session.identity && record.engineFormatVersion == 3 &&
                    record.engineState.contentEquals(session.engineState) &&
                    record.moveCount == checkpoint.admitted.moveCount && record.result == checkpoint.admitted.result) {
                    "Go terminal checkpoint differs from its archive or provenance"
                }
            } else require(archived == null) { "An archived Go branch cannot be ongoing" }
        }
    }
}

internal class GoLocalSessionBackupAdapter(
    private val admit: (GoSavedSession) -> GoAdmittedLocalSession = ::admitLocalGoSession,
) : GoLocalBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5,
        BackupStorageSlot(BackupStorageTable.GO_LOCAL_SESSION, GameType.GO), 1, 10,
        GoSessionFileCodec.MAX_FILE_BYTES * 2 + 256)

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> =
        encodeRows(database.goLocalSessionDao().listAll())

    internal fun encodeRows(rows: List<GoLocalSessionEntity>): List<GameBackupRecord> {
        require(rows.size <= 1)
        return rows.map { row ->
            require(row.gameTypeCode == GameType.GO.code && row.envelopeVersion == 3 && row.updatedAtEpochMillis >= 0)
            record(row.checkpoint.copyOf(), row.updatedAtEpochMillis).also { decode(it) }
        }
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        decode(record)
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
        val (session, time) = decode(record)
        val admitted = admit(session.copy(engineState = session.engineState.copyOf()))
        require(GoSessionFileCodec.encode(admitted.session()).contentEquals(GoSessionFileCodec.encode(session))) {
            "Go backup checkpoint is not canonical or has incorrect metadata"
        }
        return PreparedGoLocalBackup(admitted, time)
    }

    private fun decode(record: GameBackupRecord): Pair<GoSavedSession, Long> {
        GoBackupWire.header(record, KIND, 4)
        val bytes = GoBackupWire.unhex(record.fields[3], GoSessionFileCodec.MAX_FILE_BYTES)
        require(bytes.size > 4 && bytes[4] == 3.toByte()) { "Go backup requires GOS1 v3" }
        val session = requireNotNull(GoSessionFileCodec.decode(bytes))
        require(GoSessionFileCodec.encode(session).contentEquals(bytes))
        return session to GoBackupWire.time(record.fields[2])
    }

    private fun record(bytes: ByteArray, time: Long) = GameBackupRecord(KIND,
        listOf(GameType.GO.code.toString(), "1", time.toString(), GoBackupWire.hex(bytes)))

    companion object { const val KIND = "GO_LOCAL_SESSION" }
}

internal class PreparedGoLocalBackup internal constructor(
    val admitted: GoAdmittedLocalSession,
    private val time: Long,
) : PreparedGameBackup {
    override val storageSlot = BackupStorageSlot(BackupStorageTable.GO_LOCAL_SESSION, GameType.GO)
    override suspend fun write(database: MocsDatabase) {
        database.goLocalSessionDao().save(GoLocalSessionEntity(GameType.GO.code, 3,
            GoSessionFileCodec.encode(admitted.session()), time))
    }
}

/** A missing historical source remains missing; it is never invented from an engine history. */
internal class GoLocalRecordBackupAdapter(
    private val admit: (GameRecord) -> Unit = ::requireCompletedGoRecord,
) : GoLocalBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5,
        BackupStorageSlot(BackupStorageTable.GO_RECORD_SOURCE, GameType.GO), 200_000, 11,
        GameRecordPayloadPolicy.GO_MAX_BYTES * 2 + 4_096)

    override suspend fun read(database: MocsDatabase) = encodeRows(
        database.gameRecordDao().listAll().filter { it.gameTypeCode == GameType.GO.code },
        database.goRecordSourceDao().listAll(),
    )

    internal fun encodeRows(rows: List<GameRecordEntity>, sources: List<GoRecordSourceEntity>): List<GameBackupRecord> {
        require(rows.map { it.recordId }.distinct().size == rows.size)
        require(sources.map { it.recordId }.distinct().size == sources.size)
        val recordIds = rows.map { it.recordId }.toSet()
        val byId = sources.associateBy { source ->
            require(source.gameTypeCode == GameType.GO.code)
            source.recordId
        }
        require(recordIds.containsAll(byId.keys)) { "Orphan Go record provenance" }
        return rows.sortedWith(compareBy({ it.completedAtEpochMillis }, { it.recordId })).map { row ->
            require(row.gameTypeCode == GameType.GO.code)
            val record = requireNotNull(row.toGameRecord())
            val source = byId[row.recordId]?.let {
                GoSessionIdentity(it.sessionId, requireNotNull(GoSessionOrigin.entries.singleOrNull { origin ->
                    origin.code == it.originCode
                }), it.archiveRevision)
            }
            encode(record, source).also { decode(it) }
        }
    }

    internal fun encode(record: GameRecord, source: GoSessionIdentity?): GameBackupRecord {
        require(record.gameType == GameType.GO)
        requireSource(record, source)
        return GameBackupRecord(KIND, listOf(GameType.GO.code.toString(), "1", GoBackupWire.hex(record.recordId.toByteArray()),
            record.mode.code.toString(), record.difficulty?.code?.toString() ?: "-", resultCode(record.result).toString(),
            record.engineFormatVersion.toString(), GoBackupWire.hex(record.engineState), record.moveCount.toString(),
            if (record.isFavorite) "1" else "0", if (record.isEndgame) "1" else "0",
            record.completedAtEpochMillis.toString(), record.playerIndex.toString(),
            source?.sessionId ?: "-", source?.origin?.code?.toString() ?: "-", source?.archiveRevision?.toString() ?: "-"))
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        decode(record)
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
        val (archive, source) = decode(record)
        // Give admissions owned bytes too; a test seam must not mutate the prepared replacement.
        admit(archive.defensiveCopy())
        return PreparedGoRecordBackup(archive, source)
    }

    private fun decode(wire: GameBackupRecord): Pair<GameRecord, GoSessionIdentity?> {
        GoBackupWire.header(wire, KIND, 16)
        val fields = wire.fields
        val source = if (fields[13] == "-") {
            require(fields[14] == "-" && fields[15] == "-") { "Partial Go record provenance" }
            null
        } else GoSessionIdentity(fields[13], requireNotNull(GoSessionOrigin.entries.singleOrNull {
            it.code == GoBackupWire.integer(fields[14])
        }), GoBackupWire.integer(fields[15]))
        val idBytes = GoBackupWire.unhex(fields[2], GameRecord.MAX_RECORD_ID_LENGTH * 4)
        val id = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(idBytes)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("Invalid Go backup record ID UTF-8", error)
        }
        val archive = GameRecord(id, GameType.GO,
            requireNotNull(StoredGameMode.entries.singleOrNull { it.code == GoBackupWire.integer(fields[3]) }),
            fields[4].takeUnless { it == "-" }?.let { value -> requireNotNull(Difficulty.entries.singleOrNull {
                it.code == GoBackupWire.integer(value)
            }) },
            requireNotNull(listOf(GameResult.FIRST_PLAYER_WIN, GameResult.SECOND_PLAYER_WIN, GameResult.DRAW)
                .singleOrNull { resultCode(it) == GoBackupWire.integer(fields[5]) }),
            GoBackupWire.unhex(fields[7], GameRecordPayloadPolicy.GO_MAX_BYTES), GoBackupWire.integer(fields[8]),
            GoBackupWire.flag(fields[9]), GoBackupWire.flag(fields[10]), GoBackupWire.time(fields[11]),
            GoBackupWire.integer(fields[12]), GoBackupWire.integer(fields[6]))
        requireSource(archive, source)
        return archive to source
    }

    private fun requireSource(record: GameRecord, source: GoSessionIdentity?) {
        if (source == null) return
        require(source.recordId == record.recordId && record.mode == StoredGameMode.LOCAL_TWO_PLAYER &&
            record.difficulty == null && !record.isEndgame && record.playerIndex == 0) { "Incompatible local Go provenance" }
    }

    private fun resultCode(result: GameResult) = when (result) {
        GameResult.FIRST_PLAYER_WIN -> 1; GameResult.SECOND_PLAYER_WIN -> 2; GameResult.DRAW -> 3
        GameResult.ONGOING -> error("Ongoing Go record cannot be backed up as completed")
    }

    companion object { const val KIND = "GO_LOCAL_RECORD" }
}

internal class PreparedGoRecordBackup internal constructor(record: GameRecord, val source: GoSessionIdentity?) : PreparedGameBackup {
    private val owned = record.defensiveCopy()
    val recordId: String get() = owned.recordId
    override val completedRecordIds: Set<String> get() = setOf(owned.recordId)
    fun record() = owned.defensiveCopy()
    override val storageSlot = BackupStorageSlot(BackupStorageTable.GO_RECORD_SOURCE, GameType.GO)
    override suspend fun write(database: MocsDatabase) {
        check(database.gameRecordDao().insert(owned.toGameRecordEntity()) != -1L)
        source?.let {
            check(database.goRecordSourceDao().insert(GoRecordSourceEntity(owned.recordId, GameType.GO.code,
                it.sessionId, it.origin.code, it.archiveRevision)) != -1L)
        }
    }
}

/** One bundle owns both kinds so duplicate wire records cannot silently replace progress. */
internal class GoProgressBackupAdapter : GoLocalBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5,
        BackupStorageSlot(BackupStorageTable.GO_PROGRESS, GameType.GO), 1, 12, 32_768)

    override suspend fun read(database: MocsDatabase) = encodeRows(database.goProgressDao().listAll())

    internal fun encodeRows(rows: List<GoProgressEntity>): List<GameBackupRecord> {
        require(rows.size <= 2 && rows.map { it.kindCode }.distinct().size == rows.size)
        val byKind = rows.associateBy { row ->
            val kind = requireNotNull(GoProgressKind.entries.singleOrNull { it.code == row.kindCode })
            require(row.gameTypeCode == GameType.GO.code && row.formatVersion == 1 &&
                row.contentVersion == kind.contentVersion && row.updatedAtEpochMillis >= 0)
            kind
        }
        if (byKind.isEmpty()) return emptyList()
        val fields = mutableListOf(GameType.GO.code.toString(), "1")
        GoProgressKind.entries.forEach { kind ->
            val row = byKind[kind]
            fields += row?.updatedAtEpochMillis?.toString() ?: "-"
            fields += row?.let { GoBackupWire.hex(kind.normalize(it.payload.copyOf())) } ?: "-"
        }
        return listOf(GameBackupRecord(KIND, fields).also { decode(it) })
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        decode(record)
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup = PreparedGoProgressBackup(decode(record))

    private fun decode(record: GameBackupRecord): List<GoProgressEntity> {
        GoBackupWire.header(record, KIND, 6)
        val rows = GoProgressKind.entries.mapIndexedNotNull { index, kind ->
            val time = record.fields[2 + index * 2]
            val payload = record.fields[3 + index * 2]
            if (time == "-") {
                require(payload == "-")
                null
            } else {
                val bytes = GoBackupWire.unhex(payload, 4_096)
                require(kind.normalize(bytes).contentEquals(bytes)) { "Non-canonical Go progress backup" }
                GoProgressEntity(GameType.GO.code, kind.code, 1, kind.contentVersion, bytes, GoBackupWire.time(time))
            }
        }
        require(rows.isNotEmpty()) { "Empty Go progress bundle" }
        return rows
    }

    companion object { const val KIND = "GO_PROGRESS" }
}

internal class PreparedGoProgressBackup internal constructor(rows: List<GoProgressEntity>) : PreparedGameBackup {
    private val owned = rows.map { it.copy(payload = it.payload.copyOf()) }
    internal fun rows() = owned.map { it.copy(payload = it.payload.copyOf()) }
    override val storageSlot = BackupStorageSlot(BackupStorageTable.GO_PROGRESS, GameType.GO)
    override suspend fun write(database: MocsDatabase) {
        owned.forEach { database.goProgressDao().save(it.copy(payload = it.payload.copyOf())) }
    }
}

/** New Go kinds use one canonical spelling; no permissive legacy numeric/hex aliases exist. */
private object GoBackupWire {
    fun header(record: GameBackupRecord, kind: String, count: Int) {
        require(record.kind == kind && record.fields.size == count && record.fields[0] == GameType.GO.code.toString() &&
            record.fields[1] == "1") { "Invalid Go backup envelope" }
    }

    fun integer(value: String): Int = requireNotNull(value.toIntOrNull()).also {
        require(it >= 0 && it.toString() == value) { "Invalid Go backup integer" }
    }
    fun time(value: String): Long = requireNotNull(value.toLongOrNull()).also {
        require(it >= 0 && it.toString() == value) { "Invalid Go backup time" }
    }
    fun flag(value: String): Boolean { require(value == "0" || value == "1"); return value == "1" }
    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) { bytes.forEach {
            val value = it.toInt() and 255
            append(digits[value ushr 4]); append(digits[value and 15])
        } }
    }
    fun unhex(value: String, maximum: Int): ByteArray {
        require(value.length % 2 == 0 && value.length / 2 in 1..maximum && value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Invalid Go backup hex or size"
        }
        return ByteArray(value.length / 2) { index ->
            ((value[index * 2].digitToInt(16) shl 4) or value[index * 2 + 1].digitToInt(16)).toByte()
        }
    }
}
