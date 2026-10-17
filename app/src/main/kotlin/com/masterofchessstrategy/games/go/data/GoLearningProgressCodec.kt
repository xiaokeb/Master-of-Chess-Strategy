package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.games.go.GoLearningLibrary
import com.masterofchessstrategy.games.go.GoLearningProgress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.zip.CRC32

/** Full lesson identities, not a generic four-step completion bit. */
internal object GoLearningProgressCodec {
    const val MAX_BYTES = 4096

    fun encode(progress: GoLearningProgress): ByteArray {
        val frozen = freeze(progress)
        val completed = GoLearningLibrary.lessons.map { it.id }.filter { it in frozen.completedLessonIds }
        val payload = "GLP1\n1\n${GoLearningLibrary.CONTENT_VERSION}\n${frozen.lastLessonId}\n${completed.joinToString(",")}\n"
        val crc = CRC32().apply { update(payload.toByteArray(Charsets.UTF_8)) }.value.toString(16).padStart(8, '0')
        return "$payload$crc\n".toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): GoLearningProgress? {
        if (bytes.size !in 1..MAX_BYTES) return null
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val fields = text.split('\n')
            if (fields.size != 7 || fields[0] != "GLP1" || fields[1] != "1" ||
                fields[2] != GoLearningLibrary.CONTENT_VERSION.toString() || fields[6].isNotEmpty()) return null
            val completed = fields[4].takeIf { it.isNotEmpty() }?.split(',') ?: emptyList()
            if (completed.size != completed.toSet().size) return null
            freeze(GoLearningProgress(completed.toSet(), fields[3])).takeIf { encode(it).contentEquals(bytes) }
        } catch (_: IllegalArgumentException) { null }
        catch (_: java.nio.charset.CharacterCodingException) { null }
    }

    fun freeze(progress: GoLearningProgress) = GoLearningProgress(
        Collections.unmodifiableSet(progress.completedLessonIds.toSet()), progress.lastLessonId,
    )
}
