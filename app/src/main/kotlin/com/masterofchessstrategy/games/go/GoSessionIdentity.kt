package com.masterofchessstrategy.games.go

import java.security.MessageDigest
import java.util.UUID

/** Provenance only: none of these local-session sources grants ranked wins or rewards. */
enum class GoSessionOrigin(val code: Int) {
    LEGACY_UNKNOWN(0), LOCAL(1), SGF_IMPORT(2),
}

/** A terminal undo forks the future record, while retaining the active session's identity. */
data class GoSessionIdentity(
    val sessionId: String,
    val origin: GoSessionOrigin,
    val archiveRevision: Int = 0,
) {
    init {
        require(sessionId.matches(ID_PATTERN)) { "Invalid Go session identity" }
        require(archiveRevision >= 0) { "Invalid Go archive revision" }
    }

    // Domain separation and the full hash keep both legacy IDs and branch IDs within 64 chars.
    val recordId: String get() = sha256Hex(
        "MasterofChessStrategy:go-local-record:v1\u0000$sessionId\u0000$archiveRevision"
            .toByteArray(Charsets.UTF_8),
    )

    fun afterTerminalUndo(): GoSessionIdentity {
        check(archiveRevision < Int.MAX_VALUE) { "围棋归档分支已达上限，无法撤销终局" }
        return copy(archiveRevision = archiveRevision + 1)
    }

    companion object {
        internal const val MAX_ID_BYTES = 80
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")

        fun create(origin: GoSessionOrigin = GoSessionOrigin.LOCAL) =
            GoSessionIdentity(UUID.randomUUID().toString(), origin)

        /** Call only after validating the historical envelope; reading never rewrites its bytes. */
        internal fun fromLegacy(originalEnvelope: ByteArray) = GoSessionIdentity(
            "legacy-${sha256Hex(originalEnvelope)}", GoSessionOrigin.LEGACY_UNKNOWN,
        )

        private fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = "0123456789abcdef"
            return buildString(64) {
                for (byte in digest) {
                    val value = byte.toInt() and 255
                    append(hex[value ushr 4]); append(hex[value and 15])
                }
            }
        }
    }
}
