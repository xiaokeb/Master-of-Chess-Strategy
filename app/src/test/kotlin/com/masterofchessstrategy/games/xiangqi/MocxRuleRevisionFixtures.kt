package com.masterofchessstrategy.games.xiangqi

import com.masterofchessstrategy.engine.GameResult
import java.util.zip.CRC32

internal data class MocxRuleRevisionFixture(
    val id: String,
    val version: Int,
    val revision: Int,
    val result: GameResult,
    val engineState: ByteArray,
)

/** Native-generated terminal states; JVM tests verify transport, not chess legality. */
internal object MocxRuleRevisionFixtures {
    fun all(): List<MocxRuleRevisionFixture> = checkNotNull(javaClass.classLoader
        ?.getResourceAsStream("games/xiangqi/rule-revision-snapshots.txt"))
        .bufferedReader().use { reader ->
            reader.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                val fields = line.split('|')
                require(fields.size == 5 && fields[4].length % 2 == 0)
                val bytes = fields[4].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val version = fields[1].toInt()
                val revision = fields[2].toInt()
                val result = GameResult.valueOf(fields[3])
                require(bytes[4].toInt() == version && (version != 4 || bytes[bytes.size - 5].toInt() == revision))
                require(result == when (bytes[9].toInt()) {
                    1 -> GameResult.FIRST_PLAYER_WIN
                    2 -> GameResult.SECOND_PLAYER_WIN
                    3 -> GameResult.DRAW
                    else -> error("Fixture must have a real terminal result")
                })
                val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
                val stored = (0 until 4).fold(0L) { value, index ->
                    value or ((bytes[bytes.size - 4 + index].toLong() and 0xffL) shl (8 * index))
                }
                require(crc == stored) { "Fixture CRC differs: ${fields[0]}" }
                MocxRuleRevisionFixture(fields[0], version, revision, result, bytes)
            }.toList()
        }
}
