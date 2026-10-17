package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import java.util.Collections

/** Fixed registration snapshot. Missing modules are an error, never permission to discard data. */
internal class GameBackupRegistry(
    adapters: List<GameBackupAdapter>,
    private val maximumExtensionBytes: Int = GameBackupRecord.MAX_BYTES,
) {
    private data class Registration(val descriptor: GameBackupDescriptor, val adapter: GameBackupAdapter)
    private val registrations = adapters.map { Registration(it.descriptor, it) }
    private val byKind: Map<String, Registration>
    private val supportedSlots: Set<BackupStorageSlot>

    init {
        require(maximumExtensionBytes in 1..GameBackupRecord.MAX_BYTES)
        require(registrations.none { it.descriptor.kind in CORE_KINDS }) { "An extension cannot own a core backup kind" }
        require(registrations.none { it.descriptor.storageSlot == CHINESE_ACTIVE }) { "The core owns Chinese chess active rows" }
        require(registrations.map { it.descriptor.kind }.distinct().size == registrations.size) { "Duplicate backup kind" }
        require(registrations.map { it.descriptor.storageSlot }.distinct().size == registrations.size) { "Duplicate backup slot" }
        byKind = registrations.associateBy { it.descriptor.kind }
        supportedSlots = registrations.map { it.descriptor.storageSlot }.toSet() + CHINESE_ACTIVE
    }

    fun validate(records: List<GameBackupRecord>, containerVersion: Int) {
        requireFixedRegistrations()
        require(containerVersion in 1..5) { "Unsupported backup container version" }
        require(records.size <= 200_000) { "Too many backup records" }
        val counts = mutableMapOf<String, Int>()
        var byteCount = 0L
        records.forEach { record ->
            byteCount += record.encodedSizeBytes.toLong() + 1L // Canonical LF after each record.
            require(byteCount <= maximumExtensionBytes) { "Game backups exceed the aggregate size limit" }
            val owner = registration(record)
            val descriptor = owner.descriptor
            require(containerVersion >= descriptor.minimumContainerVersion) { "Backup kind requires a newer container" }
            val count = (counts[record.kind] ?: 0) + 1
            require(count <= descriptor.maximumRecords) { "Too many game records" }
            counts[record.kind] = count
            owner.adapter.validate(record, containerVersion)
            requireFixedRegistrations()
        }
    }

    fun canonicalRecords(records: List<GameBackupRecord>, containerVersion: Int): List<GameBackupRecord> {
        val snapshot = records.toList()
        validate(snapshot, containerVersion)
        return Collections.unmodifiableList(snapshot.sortedWith(
            compareBy<GameBackupRecord> { byKind.getValue(it.kind).descriptor.canonicalOrder }.thenBy { it.kind },
        ))
    }

    /** Keep legacy parsing in the game codec; only canonical records enter the shared snapshot. */
    fun normalizeRecord(record: GameBackupRecord, containerVersion: Int): GameBackupRecord {
        requireFixedRegistrations()
        require(containerVersion in 1..5) { "Unsupported backup container version" }
        val owner = requireNotNull(byKind[record.kind]) { "Backup kind requires an unavailable game adapter" }
        require(containerVersion >= owner.descriptor.minimumContainerVersion) { "Backup kind requires a newer container" }
        val normalized = owner.adapter.normalize(record, containerVersion)
        requireFixedRegistrations()
        require(normalized.kind == record.kind) { "A codec normalized a foreign record kind" }
        validate(listOf(normalized), containerVersion)
        return normalized
    }

    fun containerVersion(records: List<GameBackupRecord>): Int {
        requireFixedRegistrations()
        val version = records.fold(4) { current, record -> maxOf(current, registration(record).descriptor.minimumContainerVersion) }
        validate(records, version)
        return version
    }

    fun prepareRestore(records: List<GameBackupRecord>, containerVersion: Int,
        coreRecordIds: Set<String> = emptySet(),
    ): List<PreparedGameBackup> {
        val coreIds = coreRecordIds.toSet()
        val prepared = Collections.unmodifiableList(canonicalRecords(records, containerVersion).map { record ->
            val owner = registration(record)
            owner.adapter.prepareRestore(record).also {
                requireFixedRegistrations()
                require(it.storageSlot == owner.descriptor.storageSlot) { "Prepared backup belongs to a foreign slot" }
            }
        })
        val extensionIds = prepared.flatMap { it.completedRecordIds.toList() }
        require(extensionIds.all { it.isNotBlank() && it.length <= 64 } &&
            extensionIds.distinct().size == extensionIds.size && extensionIds.none { it in coreIds }) {
            "Duplicate or invalid completed record identity across backup owners"
        }
        registrations.forEach { owner ->
            (owner.adapter as? GameBackupSnapshotGuard)?.validatePreparedSnapshot(prepared)
            requireFixedRegistrations()
        }
        return prepared
    }

    fun requireSupportedSlots(nonEmptySlots: Set<BackupStorageSlot>) {
        requireFixedRegistrations()
        require(supportedSlots.containsAll(nonEmptySlots)) { "Stored game data requires an unavailable backup adapter" }
    }

    /** The registry, not a caller's mutable adapter list, owns snapshot reads. */
    suspend fun readRecords(database: MocsDatabase): List<GameBackupRecord> {
        requireFixedRegistrations()
        val records = registrations.flatMap { owner ->
            val read = owner.adapter.read(database).toList()
            requireFixedRegistrations()
            require(read.all { it.kind == owner.descriptor.kind }) { "Adapter read a foreign record kind" }
            read
        }
        return canonicalRecords(records, containerVersion(records))
    }

    private fun requireFixedRegistrations() {
        require(registrations.all { it.adapter.descriptor == it.descriptor }) { "Backup registration changed after construction" }
    }

    private fun registration(record: GameBackupRecord): Registration {
        val owner = requireNotNull(byKind[record.kind]) { "Backup kind requires an unavailable game adapter" }
        require(owner.adapter.descriptor == owner.descriptor) { "Backup registration changed after construction" }
        require(record.fields.first() == owner.descriptor.storageSlot.gameType.code.toString()) { "Backup record has foreign game ownership" }
        require(record.encodedSizeBytes <= owner.descriptor.maximumRecordBytes) { "Game backup record exceeds its limit" }
        return owner
    }

    private companion object {
        val CHINESE_ACTIVE = BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.CHINESE_CHESS)
        val CORE_KINDS = setOf("CREATED", "ACTIVE", "SELECTION", "SETTINGS", "TUTORIAL", "OUTCOME", "RECORD", "ENDGAME", "SHA256")
    }
}
