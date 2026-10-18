package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.games.go.GoAiMatchFileCodec
import com.masterofchessstrategy.games.go.GoAiSavedMatch
import java.security.MessageDigest

/** Owned by one preparation context, never by a long-lived adapter or a prepared row. */
internal class GoAiBackupAdmissionCache(
    private val admit: (GoAiSavedMatch) -> GoAdmittedAiCheckpoint,
    private val fingerprint: (ByteArray) -> String = { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
    },
) {
    private class Item(val bytes: ByteArray, val admitted: GoAdmittedAiCheckpoint)
    private val buckets = mutableMapOf<String, MutableList<Item>>()

    fun prepare(bytes: ByteArray): GoAdmittedAiCheckpoint {
        val owned = bytes.copyOf()
        val match = GoAiBackupWire.canonical(owned)
        val bucket = fingerprint(owned.copyOf())
        buckets[bucket]?.firstOrNull { it.bytes.contentEquals(owned) }?.let { return it.admitted }
        val admitted = admit(match)
        require(GoAiMatchFileCodec.encode(admitted.match()).contentEquals(owned)) {
            "Go AI backup admission changed canonical checkpoint metadata"
        }
        // Failed admission is never cached. Hash collisions still compare the entire image.
        buckets.getOrPut(bucket) { mutableListOf() }.add(Item(owned, admitted))
        return admitted
    }
}
