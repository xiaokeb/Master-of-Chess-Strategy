package com.masterofchessstrategy.data

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Budget each UTF-8 line before appending; do not first concatenate an oversized container. */
internal class BackupWireBuilder(
    private val maximumBytes: Int = GameBackupRecord.MAX_BYTES,
    private val maximumLines: Int = 200_000,
) {
    private val output = ByteArrayOutputStream()
    private val digest = MessageDigest.getInstance("SHA-256")
    private var lineCount = 0
    private var finished = false

    init {
        require(maximumBytes in CHECKSUM_LINE_BYTES..GameBackupRecord.MAX_BYTES)
        require(maximumLines in 2..200_000)
    }

    fun add(line: String) {
        check(!finished) { "Backup encoding already finished" }
        require(line.isNotEmpty() && line.none { it == '\r' || it == '\n' || it == '\u0000' })
        require(line.length <= maximumBytes) { "Backup line exceeds the byte budget" }
        val bytes = line.toByteArray(Charsets.UTF_8)
        require(bytes.toString(Charsets.UTF_8) == line) { "Backup line is not valid UTF-8" }
        require(lineCount < maximumLines) { "Backup has too many lines" }
        require(output.size().toLong() + bytes.size + 1L + CHECKSUM_LINE_BYTES <= maximumBytes) {
            "Backup exceeds its final byte budget"
        }
        output.write(bytes)
        output.write(10)
        digest.update(bytes)
        digest.update(10.toByte())
        lineCount++
    }

    fun finish(): ByteArray {
        check(!finished) { "Backup encoding already finished" }
        require(lineCount >= 2) { "Backup header and creation record are required" }
        finished = true
        val checksum = digest.digest().joinToString("") { byte ->
            HEX[(byte.toInt() ushr 4) and 15].toString() + HEX[byte.toInt() and 15]
        }
        output.write("SHA256|$checksum\n".toByteArray(Charsets.UTF_8))
        return output.toByteArray()
    }

    private companion object {
        const val CHECKSUM_LINE_BYTES = 7 + 64 + 1
        const val HEX = "0123456789abcdef"
    }
}
