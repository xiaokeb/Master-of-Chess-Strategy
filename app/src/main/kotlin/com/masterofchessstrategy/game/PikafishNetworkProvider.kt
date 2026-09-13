package com.masterofchessstrategy.game

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal data class PikafishNetworkSpec(
    val assetPath: String,
    val installedFileName: String,
    val byteCount: Long,
    val sha256: String,
)

/**
 * Installs the bundled NNUE network into no-backup private storage.
 *
 * Pikafish requires a filesystem path. The asset is copied lazily on the AI
 * dispatcher, verified before publication, and reused for the process lifetime.
 */
internal class PikafishNetworkProvider(
    private val targetDirectory: File,
    private val openNetworkAsset: () -> InputStream,
    private val spec: PikafishNetworkSpec = PRODUCTION_SPEC,
) {
    private var cachedPath: String? = null

    constructor(context: Context) : this(
        targetDirectory = File(context.noBackupFilesDir, "ai"),
        openNetworkAsset = {
            context.assets.open(PRODUCTION_SPEC.assetPath)
        },
    )

    @Synchronized
    fun requireNetworkPath(): String {
        cachedPath?.let { path ->
            if (File(path).isFile) return path
        }

        check(targetDirectory.isDirectory || targetDirectory.mkdirs()) {
            "Could not create the private AI directory"
        }
        val destination = File(targetDirectory, spec.installedFileName)
        if (isVerified(destination)) {
            return destination.absolutePath.also { cachedPath = it }
        }

        val temporary = File.createTempFile(
            "pikafish-",
            ".nnue.tmp",
            targetDirectory,
        )
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copiedBytes = 0L
            openNetworkAsset().buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copiedBytes += count
                    }
                }
            }
            check(
                copiedBytes == spec.byteCount &&
                    digest.digest().toHex() == spec.sha256,
            ) {
                "Bundled Pikafish network failed integrity verification"
            }
            check(!destination.exists() || destination.delete()) {
                "Could not replace the private Pikafish network"
            }
            check(temporary.renameTo(destination)) {
                "Could not publish the private Pikafish network"
            }
            return destination.absolutePath.also { cachedPath = it }
        } finally {
            if (temporary.exists()) {
                temporary.delete()
            }
        }
    }

    private fun isVerified(file: File): Boolean =
        file.isFile &&
            file.length() == spec.byteCount &&
            file.inputStream().buffered().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().toHex() == spec.sha256
            }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private companion object {
        const val COPY_BUFFER_BYTES = 1024 * 1024

        val PRODUCTION_SPEC = PikafishNetworkSpec(
            assetPath = "pikafish/pikafish.nnue",
            installedFileName = "pikafish-2026-09-06.nnue",
            byteCount = 50_706_378,
            sha256 = "7d13d73569a9b571ba0eb20cf1596247bc2a42738967e61afef6482b231e900e",
        )
    }
}
