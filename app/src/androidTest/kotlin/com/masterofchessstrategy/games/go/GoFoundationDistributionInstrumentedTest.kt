package com.masterofchessstrategy.games.go

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.GoSearchBudget
import com.masterofchessstrategy.engine.GoSearchResult
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.NativeGoSearchRuntime
import com.masterofchessstrategy.ui.KATAGO_DEPENDENCY_SOURCE_ASSET
import com.masterofchessstrategy.ui.copyLegalAsset
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Low-level production chain; deliberately independent of unfinished AI page/session code. */
@RunWith(AndroidJUnit4::class)
class GoFoundationDistributionInstrumentedTest {
    @Test
    fun actualAssetsPrepareAtomicFileAndCompleteOneNativeSearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.noBackupFilesDir.canonicalFile
        val directory = File(root, "go-foundation-instrumented-${UUID.randomUUID()}")
        check(!directory.exists()) { "A fresh owned directory is required" }
        try {
            val files = GoAiModelFiles(directory)
            var openedAssets = 0
            val model = files.prepare(sourceAsset = {
                openedAssets++
                context.assets.open(MODEL_ASSET)
            })
            assertEquals(1, openedAssets)
            assertEquals(MODEL_BYTES, model.length())
            assertEquals(MODEL_SHA, sha256(model))
            assertEquals(directory.canonicalFile, model.canonicalFile.parentFile)

            // The second read must verify the published cache, not open or replace an asset.
            val reused = files.prepare(sourceAsset = { error("Verified cache reopened the source") })
            assertEquals(model.canonicalFile, reused.canonicalFile)

            NativeGoEngine(9).use { game ->
                val before = game.serialize()
                NativeGoSearchRuntime(model.absolutePath).use { runtime ->
                    runtime.prepare(before, GoSearchBudget(maxVisits = 1, maxTimeMillis = 20_000)).use { request ->
                        val suggestion = request.run() as? GoSearchResult.Move
                            ?: error("Expected one real NN search move")
                        assertEquals(1, suggestion.metrics.visits)
                        assertArrayEquals(before, game.serialize())
                        assertTrue(game.legalActions().contains(suggestion.action))
                        assertEquals(ActionResult.Accepted, game.apply(suggestion.action))
                    }
                }
            }
        } finally {
            // Never clear the app's production cache or user data. This UUID is owned by this test.
            check(directory.canonicalFile.parentFile == root)
            check(directory.name.matches(Regex("go-foundation-instrumented-[0-9a-f-]{36}")))
            if (directory.exists()) check(directory.deleteRecursively())
        }
    }

    @Test
    fun exactDependencySourceAndNetworkLicenseAreAccessibleFromInstalledAssets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val copied = ByteArrayOutputStream()
        copyLegalAsset(
            openSource = { context.assets.open(KATAGO_DEPENDENCY_SOURCE_ASSET) },
            openDestination = { copied },
        )
        val bytes = copied.toByteArray()
        assertEquals(1_627_442, bytes.size)
        assertEquals(SOURCE_ZIP_SHA, sha256(bytes))
        val terms = context.assets.open("games/go/legal/KataGo-network-license.html")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertTrue(terms.contains("KataGo Neural Network License"))
        assertEquals(NETWORK_LICENSE_SHA, sha256(terms.toByteArray(Charsets.UTF_8)))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MODEL_ASSET = "games/go/ai/kata1-b15c192-s1672170752-d466197061.txt.gz.bin"
        const val MODEL_BYTES = 48_038_914L
        const val MODEL_SHA = "09456899f1b9155217f4ca059cf8a68f79b7eba22cf6b7c00ffb7f17ce067967"
        const val SOURCE_ZIP_SHA = "2135faa55673f757df0a556a832cdfe2dd5f1698b6a3f95dfdf2d3557bce1211"
        const val NETWORK_LICENSE_SHA = "590837548c66daca592955910aeeea36634daa49c722b1a80f6e24c0b714657e"
    }
}
