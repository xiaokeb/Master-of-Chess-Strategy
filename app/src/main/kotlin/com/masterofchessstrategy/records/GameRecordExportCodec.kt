package com.masterofchessstrategy.records

import com.masterofchessstrategy.data.GameRecord
import java.security.MessageDigest

/** Stable, explicit UTF-8 export envelope for one completed game. */
internal object GameRecordExportCodec {
    const val MIME_TYPE = "application/json"
    const val FILE_EXTENSION = ".mocs-xq.json"

    fun encode(record: GameRecord): ByteArray {
        val state = record.engineState.joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(record.engineState)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return buildString {
            appendLine("{")
            appendLine("  \"format\": \"mocs-chinese-chess-record\",")
            appendLine("  \"version\": 1,")
            appendLine("  \"recordId\": \"${record.recordId.jsonEscape()}\",")
            appendLine("  \"modeCode\": ${record.mode.code},")
            appendLine("  \"difficultyCode\": ${record.difficulty?.code ?: "null"},")
            appendLine("  \"result\": \"${record.result.name}\",")
            appendLine("  \"moveCount\": ${record.moveCount},")
            appendLine("  \"completedAtEpochMillis\": ${record.completedAtEpochMillis},")
            appendLine("  \"engineStateSha256\": \"$digest\",")
            appendLine("  \"engineStateHex\": \"$state\"")
            appendLine("}")
        }.toByteArray(Charsets.UTF_8)
    }

    private fun String.jsonEscape(): String = buildString {
        this@jsonEscape.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }
}
