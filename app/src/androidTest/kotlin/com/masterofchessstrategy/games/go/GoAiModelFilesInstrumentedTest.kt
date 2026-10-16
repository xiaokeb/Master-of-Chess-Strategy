package com.masterofchessstrategy.games.go

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** UUID AtomicFile integrity tests; small owned fixtures are not neural networks. */
class GoAiModelFilesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "go-model-qa-${UUID.randomUUID()}")
    private val payload = ByteArray(2048) { (it % 251).toByte() }
    private val spec = GoAiModelSpec("qa.txt.gz", payload.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        })
    private val model get() = File(directory, spec.fileName)

    @After fun cleanupOnlyUuidModel() {
        assertEquals(context.cacheDir.canonicalFile, directory.canonicalFile.parentFile)
        for (name in listOf(spec.fileName, spec.fileName + ".new", spec.fileName + ".bak")) {
            val own = File(directory, name)
            assertTrue(!own.exists() || own.delete())
        }
        assertTrue(!directory.exists() || directory.delete())
    }

    @Test fun extractsChecksPublishedFileAndReusesCacheAcrossRepositoryInstances() {
        val prepared = GoAiModelFiles(directory, spec).prepare(sourceAsset = { ByteArrayInputStream(payload) })
        assertEquals(model.canonicalFile, prepared.canonicalFile)
        assertArrayEquals(payload, prepared.readBytes())
        GoAiModelFiles(directory, spec).prepare(sourceAsset = { error("Valid cache must not open asset") })
        assertFalse(File(model.path + ".new").exists())
    }

    @Test fun corruptAssetKeepsPreviousCacheBytes() {
        assertTrue(directory.mkdirs())
        val original = "previous-invalid-model".toByteArray()
        model.writeBytes(original)
        val corrupt = payload.copyOf().apply { this[10] = (this[10] + 1).toByte() }
        try {
            GoAiModelFiles(directory, spec).prepare(sourceAsset = { ByteArrayInputStream(corrupt) })
            fail("Corrupt asset accepted")
        } catch (_: IOException) { }
        assertArrayEquals(original, model.readBytes())
        assertFalse(File(model.path + ".new").exists())
    }

    @Test fun cancelledExtractionKeepsPreviousCacheAndRemovesOwnPartialWrite() {
        assertTrue(directory.mkdirs())
        val original = "previous-invalid-model".toByteArray()
        model.writeBytes(original)
        var cancelled = false
        try {
            GoAiModelFiles(directory, spec).prepare(
                sourceAsset = { cancelled = true; ByteArrayInputStream(payload) },
                cancelled = { cancelled },
            )
            fail("Cancelled extraction accepted")
        } catch (_: CancellationException) { }
        assertArrayEquals(original, model.readBytes())
        assertFalse(File(model.path + ".new").exists())
    }

    @Test fun separateRepositoryInstancesSerializeExtractionAndCacheReuse() {
        val reading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val first = Thread {
            try {
                GoAiModelFiles(directory, spec).prepare(sourceAsset = {
                    object : ByteArrayInputStream(payload) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            reading.countDown()
                            check(finish.await(5, TimeUnit.SECONDS))
                            return super.read(buffer, offset, length)
                        }
                    }
                })
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        val second = Thread {
            try {
                secondStarted.countDown()
                GoAiModelFiles(directory, spec).prepare(sourceAsset = { error("Concurrent valid cache should be reused") })
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        try {
            first.start()
            check(reading.await(3, TimeUnit.SECONDS))
            second.start()
            check(secondStarted.await(3, TimeUnit.SECONDS))
        } finally {
            finish.countDown()
            first.join(5000)
            if (second.state != Thread.State.NEW) second.join(5000)
        }
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        failure.get()?.let { throw AssertionError("Model cache concurrent extraction failed", it) }
        assertArrayEquals(payload, model.readBytes())
        assertFalse(File(model.path + ".new").exists())
    }
}
