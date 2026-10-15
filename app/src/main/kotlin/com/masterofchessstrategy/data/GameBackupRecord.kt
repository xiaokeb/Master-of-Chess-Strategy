package com.masterofchessstrategy.data

import java.util.Collections

/** Kind plus canonical wire fields. Game-specific meaning stays in its registered codec. */
internal class GameBackupRecord(val kind: String, fields: List<String>) {
    val fields: List<String> = Collections.unmodifiableList(ArrayList(fields))
    val encodedSizeBytes: Int

    init {
        require(kind.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) { "Invalid backup record kind" }
        require(this.fields.size in 1..32) { "Invalid backup field count" }
        var size = kind.length.toLong()
        this.fields.forEach { field ->
            require(field.length <= MAX_BYTES && field.none { it == '|' || it == '\r' || it == '\n' || it == '\u0000' }) {
                "Backup fields cannot contain wire separators"
            }
            val bytes = field.toByteArray(Charsets.UTF_8)
            require(bytes.toString(Charsets.UTF_8) == field) { "Backup field is not valid UTF-8" }
            size += 1L + bytes.size
            require(size <= MAX_BYTES) { "Backup record exceeds the container size limit" }
        }
        encodedSizeBytes = size.toInt()
    }

    override fun equals(other: Any?) = other is GameBackupRecord && kind == other.kind && fields == other.fields
    override fun hashCode(): Int = 31 * kind.hashCode() + fields.hashCode()

    companion object {
        const val MAX_BYTES = 128 * 1024 * 1024
    }
}
