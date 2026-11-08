package com.masterofchessstrategy.games.competition_mahjong.data

import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import java.security.MessageDigest
import java.util.Collections

/** Pure split result; it owns no database, Native handle, or permission snapshot. */
internal sealed interface CompetitionMahjongFormalBackupCandidate {
    class LegacyOnly internal constructor(records: List<GameBackupRecord>, val legacyRecord: GameBackupRecord) :
        CompetitionMahjongFormalBackupCandidate {
        val records: List<GameBackupRecord> = immutableRecords(records)
    }

    class Formal internal constructor(strictRecords: List<GameBackupRecord>, val retainedLegacyRecord: GameBackupRecord?) :
        CompetitionMahjongFormalBackupCandidate {
        /** MCLP and other owners remain present; only associated archived legacy is removed. */
        val strictRecords: List<GameBackupRecord> = immutableRecords(strictRecords)
    }
}

/** Role-aware structural wire only. Actual Native admission and transactional installation come later. */
internal object CompetitionMahjongFormalBackupWire {
    const val KIND = CompetitionMahjongFormalBackupBundle.KIND
    const val VERSION = 1
    const val MAXIMUM_RECORD_BYTES = 128 + 2 * CompetitionMahjongFormalSnapshotCodec.MAX_BYTES
    private const val LEGACY_KIND = "COMPETITION_MATCH"
    private val rolePattern = Regex("-|[0-9a-f]{64}")

    /** New wire: [game, version, viewer, MCTX hex, retained-source SHA256 or '-']. */
    fun encode(viewer: Int, mctx: ByteArray, retainedLegacySource: GameBackupRecord? = null): GameBackupRecord {
        val formal = CompetitionMahjongFormalBackupBundle.encode(viewer, mctx)
        val role = retainedLegacySource?.let(::retainedLegacySourceSha256) ?: "-"
        return GameBackupRecord(KIND, listOf("2", VERSION.toString(), formal.fields[1], formal.fields[2], role))
            .also { require(it.encodedSizeBytes <= MAXIMUM_RECORD_BYTES) }
    }

    /** Preserve old three-field spelling as old wire, and preserve version/role on new wire. */
    fun normalize(record: GameBackupRecord): GameBackupRecord {
        require(record.kind == KIND && record.encodedSizeBytes <= MAXIMUM_RECORD_BYTES)
        if (record.fields.size == 3) return CompetitionMahjongFormalBackupBundle.normalize(record)
        val fields = record.fields
        require(fields.size == 5 && fields[0] == "2" && fields[1] == VERSION.toString())
        require(rolePattern.matches(fields[4])) { "Invalid retained legacy role" }
        val formal = CompetitionMahjongFormalBackupBundle.normalize(GameBackupRecord(KIND,
            listOf(fields[0], fields[2], fields[3])))
        return GameBackupRecord(KIND, listOf("2", fields[1], formal.fields[1], formal.fields[2], fields[4]))
    }

    /** Hash the old adapter's canonical full record, including kind, every field, and final LF. */
    fun retainedLegacySourceSha256(record: GameBackupRecord): String =
        canonicalLegacySha256(canonicalLegacy(record))

    fun decodeContainer(records: List<GameBackupRecord>): CompetitionMahjongFormalBackupCandidate {
        val candidate = records.toList()
        val formalRecords = candidate.filter { it.kind == KIND }
        val legacyRecords = candidate.filter { it.kind == LEGACY_KIND }
        require(formalRecords.size <= CompetitionMahjongFormalBackupBundle.MAXIMUM_RECORDS) { "Too many formal seats" }
        require(legacyRecords.size <= 1) { "Multiple legacy sources" }
        val legacy = legacyRecords.singleOrNull()?.let(::canonicalLegacy)
        if (formalRecords.isEmpty()) {
            return if (legacy != null) CompetitionMahjongFormalBackupCandidate.LegacyOnly(
                candidate.map { if (it.kind == LEGACY_KIND) legacy else it }, legacy,
            ) else CompetitionMahjongFormalBackupCandidate.Formal(candidate, null)
        }

        val normalized = formalRecords.map(::normalize)
        val viewers = normalized.map { if (it.fields.size == 3) it.fields[1] else it.fields[2] }
        require(viewers.distinct().size == viewers.size) { "Duplicate formal seat" }
        val roles = normalized.map { if (it.fields.size == 3) "-" else it.fields[4] }
        require(roles.distinct().size == 1) { "Formal seats disagree on retained legacy source" }
        val role = roles.first()
        if (legacy == null) {
            require(role == "-") { "Retained legacy source is missing" }
        } else {
            require(normalized.all { it.fields.size == 5 } && role == canonicalLegacySha256(legacy)) {
                "Legacy and formal records lack a matching archive role"
            }
        }

        val formalIterator = normalized.iterator()
        val strictRecords = candidate.mapNotNull { record ->
            when (record.kind) {
                LEGACY_KIND -> null
                KIND -> formalIterator.next().let { formal ->
                    if (formal.fields.size == 3) formal else GameBackupRecord(KIND,
                        listOf("2", formal.fields[2], formal.fields[3]))
                }
                else -> record
            }
        }
        return CompetitionMahjongFormalBackupCandidate.Formal(strictRecords, legacy)
    }

    private fun canonicalLegacy(record: GameBackupRecord): GameBackupRecord {
        require(record.kind == LEGACY_KIND)
        // normalize performs envelope validation only, without invoking the adapter's Native admission.
        return CompetitionMahjongBackupAdapter().normalize(record, 5)
    }

    private fun canonicalLegacySha256(record: GameBackupRecord): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(record.kind.toByteArray(Charsets.UTF_8))
        record.fields.forEach { field ->
            digest.update('|'.code.toByte())
            digest.update(field.toByteArray(Charsets.UTF_8))
        }
        digest.update('\n'.code.toByte())
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}

private fun immutableRecords(records: List<GameBackupRecord>): List<GameBackupRecord> =
    Collections.unmodifiableList(ArrayList(records))
