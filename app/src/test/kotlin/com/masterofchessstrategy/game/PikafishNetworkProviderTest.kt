package com.masterofchessstrategy.game

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class PikafishNetworkProviderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun verifiedAssetIsInstalledAndReused() {
        val bytes = "verified-network".encodeToByteArray()
        var opens = 0
        val provider = provider(bytes) {
            opens += 1
        }

        val firstPath = provider.requireNetworkPath()
        val secondPath = provider.requireNetworkPath()

        assertEquals(firstPath, secondPath)
        assertEquals(1, opens)
        assertArrayEquals(bytes, java.io.File(firstPath).readBytes())
    }

    @Test
    fun invalidAssetIsRejectedWithoutPublishingIt() {
        val bytes = "unexpected".encodeToByteArray()
        val provider = PikafishNetworkProvider(
            targetDirectory = temporaryFolder.newFolder("invalid"),
            openNetworkAsset = { bytes.inputStream() },
            spec = PikafishNetworkSpec(
                assetPath = "test.nnue",
                installedFileName = "installed.nnue",
                byteCount = bytes.size.toLong(),
                sha256 = "00".repeat(32),
            ),
        )

        assertThrows(IllegalStateException::class.java) {
            provider.requireNetworkPath()
        }
        assertFalse(
            java.io.File(
                temporaryFolder.root.resolve("invalid"),
                "installed.nnue",
            ).exists(),
        )
    }

    private fun provider(
        bytes: ByteArray,
        onOpen: () -> Unit,
    ): PikafishNetworkProvider {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
        return PikafishNetworkProvider(
            targetDirectory = temporaryFolder.newFolder("valid"),
            openNetworkAsset = {
                onOpen()
                bytes.inputStream()
            },
            spec = PikafishNetworkSpec(
                assetPath = "test.nnue",
                installedFileName = "installed.nnue",
                byteCount = bytes.size.toLong(),
                sha256 = digest,
            ),
        )
    }
}
