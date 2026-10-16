package com.masterofchessstrategy.engine

import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.internal.GoSearchBindings
import com.masterofchessstrategy.engine.internal.NativeBindings
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.AfterClass
import org.junit.Test

/** Actual JNI and locked test-APK asset, not scripted AI; no production model directory is touched. */
class NativeGoSearchRuntimeInstrumentedTest {
    private fun model(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = synchronized(NativeGoSearchRuntimeInstrumentedTest::class.java) {
            ownedModel ?: run {
                val directory = File(context.cacheDir, "go-search-jni-qa-${UUID.randomUUID()}")
                check(directory.mkdir())
                val target = File(directory, "kata1-b15c192-s1672170752-d466197061.txt.gz")
                try {
                    context.assets.open("games/go/ai/${target.name}.bin").use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    ownedModel = target
                } catch (failure: Throwable) {
                    if (target.exists()) check(target.delete())
                    check(directory.delete())
                    throw failure
                }
                target
            }
        }
        check(file.canonicalFile.parentFile.parentFile == context.cacheDir.canonicalFile)
        assertEquals(48_038_914L, file.length())
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        assertEquals("09456899f1b9155217f4ca059cf8a68f79b7eba22cf6b7c00ffb7f17ce067967",
            hash.digest().joinToString("") { "%02x".format(it) })
        return file
    }

    @Test fun actualNetworkSearchesThreeSizesAndHandicapsWithCopiedFullHistory() {
        NativeGoSearchRuntime(model().path).use { runtime ->
            for (size in listOf(9, 13, 19)) for (handicap in listOf(0, 5)) {
                NativeGoEngine(size, if (handicap == 0) 15 else 0, handicap).use { game ->
                    assertEquals(ActionResult.Accepted, game.apply(GoAction.Place(BoardPosition(0, 0))))
                    assertEquals(ActionResult.Accepted, game.apply(GoAction.Place(BoardPosition(size - 1, size - 1))))
                    val before = game.serialize()
                    val input = before.copyOf()
                    val rows = runtime.processedRows()
                    runtime.prepare(input, GoSearchBudget(1, 20_000)).use { request ->
                        input.fill(0) // Native preparation must have copied it already.
                        val result = request.run() as GoSearchResult.Move
                        assertEquals(1, result.metrics.visits)
                        assertTrue(result.metrics.elapsedMillis >= 0)
                        assertTrue(runtime.processedRows() > rows)
                        assertArrayEquals(before, game.serialize())
                        assertEquals(ActionResult.Accepted, game.apply(result.action))
                        expectState { request.run() }
                    }
                }
            }
        }
    }

    @Test fun preparedRequestCapAndCancelAllDoNotPoisonFreshRequests() {
        NativeGoSearchRuntime(model().path).use { runtime ->
            NativeGoEngine(9).use { game ->
                val bytes = game.serialize()
                val prepared = (0 until 16).map { runtime.prepare(bytes, GoSearchBudget(1, 20_000)) }
                try {
                    try { runtime.prepare(bytes, GoSearchBudget(1, 20_000)).close(); fail("17th live request accepted") }
                    catch (_: RuntimeException) { }
                    runtime.cancelAll()
                    for (request in prepared) assertEquals(GoSearchResult.Cancelled, request.run())
                } finally { prepared.forEach { it.close() } }
                runtime.prepare(bytes, GoSearchBudget(1, 20_000)).use { assertTrue(it.run() is GoSearchResult.Move) }
            }
        }
    }

    @Test fun runtimeCloseCancelsPreparedRequestsAndClosesAdmissionIdempotently() {
        val runtime = NativeGoSearchRuntime(model().path)
        val bytes = NativeGoEngine(9).use { it.serialize() }
        val request = runtime.prepare(bytes, GoSearchBudget(1, 20_000))
        try {
            runtime.close(); runtime.close(); runtime.cancelAll()
            assertEquals(GoSearchResult.Cancelled, request.run())
            expectState { runtime.prepare(bytes, GoSearchBudget(1, 20_000)) }
        } finally { request.close(); request.close(); runtime.close() }
        request.cancel()
        expectState { request.run() }
    }

    @Test fun malformedAndScoringRequestsAreRejectedWithoutChangingRulesOrLeakingRunningCount() {
        NativeGoSearchRuntime(model().path).use { runtime ->
            NativeGoEngine(9).use { game ->
                val good = game.serialize()
                for (bytes in listOf(byteArrayOf(1), good.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() })) {
                    runtime.prepare(bytes, GoSearchBudget(1, 20_000)).use { expectArgument { it.run() } }
                    assertArrayEquals(good, game.serialize())
                }
                repeat(2) { assertEquals(ActionResult.Accepted, game.apply(GoAction.Pass)) }
                val scoring = game.serialize()
                runtime.prepare(scoring, GoSearchBudget(1, 20_000)).use { expectArgument { it.run() } }
                assertArrayEquals(scoring, game.serialize())
                val revision = game.scoringState().proposalRevision
                for (player in GoPlayer.entries) assertEquals(ActionResult.Accepted, game.apply(GoAction.ConfirmScore(player, revision)))
                runtime.prepare(game.serialize(), GoSearchBudget(1, 20_000)).use { expectArgument { it.run() } }
                expectArgument { runtime.prepare(byteArrayOf(), GoSearchBudget(1, 20_000)) }
                expectArgument { runtime.prepare(ByteArray(1_000_001), GoSearchBudget(1, 20_000)) }
                runtime.prepare(good, GoSearchBudget(1, 20_000)).use { assertTrue(it.run() is GoSearchResult.Move) }
            }
        }
    }

    @Test fun rawCloseJoinsObservedInferenceAndWrongTypeReleasePreservesHandles() {
        val runtime = GoSearchBindings.create(model().path)
        val game = NativeGoEngine(19)
        val goHandle = NativeBindings.createGoEngine(9, 15, 0)
        val executor = Executors.newSingleThreadExecutor()
        val request = GoSearchBindings.prepare(runtime, game.serialize(), 512, 20_000)
        try {
            GoSearchBindings.closeRequest(runtime) // Wrong type must not remove the runtime.
            GoSearchBindings.closeRuntime(goHandle)
            GoSearchBindings.closeRequest(goHandle)
            assertEquals(0, NativeBindings.goCurrentPlayer(goHandle))
            val rulesBytes = NativeBindings.serializeGo(goHandle)
            expectState { GoSearchBindings.run(runtime) }
            expectState { GoSearchBindings.prepare(request, game.serialize(), 1, 100) }
            for ((visits, time) in listOf(0 to 1L, 513 to 1L, 1 to 0L, 1 to Long.MAX_VALUE))
                expectArgument { GoSearchBindings.prepare(runtime, game.serialize(), visits, time) }
            val future = executor.submit(Callable { GoSearchResultDecoder.decode(GoSearchBindings.run(request)) })
            awaitInference(future) { GoSearchBindings.processedRows(runtime) }
            GoSearchBindings.closeRequest(request)
            assertEquals(GoSearchResult.Cancelled, future.get(30, TimeUnit.SECONDS))
            expectState { GoSearchBindings.run(request) }
            assertEquals(0, NativeBindings.goCurrentPlayer(goHandle))
            assertArrayEquals(rulesBytes, NativeBindings.serializeGo(goHandle))
            GoSearchBindings.closeRequest(request)
        } finally {
            GoSearchBindings.closeRequest(request); GoSearchBindings.closeRuntime(runtime)
            NativeBindings.releaseGoEngine(goHandle); game.close()
            executor.shutdownNow(); assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test fun concurrentRuntimeCloseJoinsObservedInferenceAndCancellationDoesNotWaitForNetwork() {
        val runtime = NativeGoSearchRuntime(model().path)
        val bytes = NativeGoEngine(19).use { it.serialize() }
        val request = runtime.prepare(bytes, GoSearchBudget(512, 20_000))
        val executor = Executors.newFixedThreadPool(3)
        try {
            val future = executor.submit(Callable { request.run() })
            awaitInference(future) { runtime.processedRows() }
            val started = System.nanoTime()
            request.cancel()
            assertTrue("Cancel waited for inference", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000)
            val first = executor.submit(Callable { runtime.close() })
            val second = executor.submit(Callable { runtime.close() })
            first.get(30, TimeUnit.SECONDS); second.get(30, TimeUnit.SECONDS)
            assertEquals(GoSearchResult.Cancelled, future.get(30, TimeUnit.SECONDS))
            expectState { runtime.prepare(bytes, GoSearchBudget(1, 20_000)) }
        } finally {
            request.close(); runtime.close()
            executor.shutdownNow(); assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test fun invalidPathsAndWrongModelCannotCreateARuntimeOrFallbackSearch() {
        val locked = model()
        for (path in listOf("relative", "/bad\u0000path", "/bad\ud800", "/" + "x".repeat(4096)))
            expectArgument { GoSearchBindings.create(path) }
        val bad = File(locked.parentFile, "bad-${UUID.randomUUID()}.txt.gz")
        try {
            bad.writeBytes(byteArrayOf(1, 2, 3))
            try { NativeGoSearchRuntime(bad.path).close(); fail("Wrong model accepted") } catch (_: RuntimeException) { }
            try { NativeGoSearchRuntime(File(locked.parentFile, "missing").path).close(); fail("Missing model accepted") }
            catch (_: RuntimeException) { }
        } finally { if (bad.exists()) assertTrue(bad.delete()) }
        NativeGoSearchRuntime(locked.path).use { assertEquals(0L, it.processedRows()) }
    }

    @Test fun concurrentRunsConsumeOneRequestExactlyOnce() {
        NativeGoSearchRuntime(model().path).use { runtime ->
            val bytes = NativeGoEngine(9).use { it.serialize() }
            runtime.prepare(bytes, GoSearchBudget(1, 20_000)).use { request ->
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val outputs = (0..1).map { executor.submit(Callable {
                        try { request.run() } catch (_: IllegalStateException) { "already consumed" }
                    }) }.map { it.get(30, TimeUnit.SECONDS) }
                    assertEquals(1, outputs.count { it is GoSearchResult.Move })
                    assertEquals(1, outputs.count { it == "already consumed" })
                } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS)) }
            }
        }
    }

    private fun awaitInference(future: Future<*>, rows: () -> Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (rows() == 0L && !future.isDone && System.nanoTime() < deadline) Thread.yield()
        assertTrue("Actual NN inference was not observed", rows() > 0L)
        assertFalse("Search ended before close race", future.isDone)
    }
    private fun expectState(block: () -> Unit) {
        try { block(); fail("Invalid lifecycle accepted") } catch (_: IllegalStateException) { }
    }
    private fun expectArgument(block: () -> Unit) {
        try { block(); fail("Invalid argument accepted") } catch (_: IllegalArgumentException) { }
    }

    companion object {
        private var ownedModel: File? = null
        @AfterClass @JvmStatic fun removeOnlyOwnedModelCopy() {
            val model = ownedModel ?: return
            val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
            check(model.canonicalFile.parentFile.parentFile == cache &&
                model.parentFile.name.startsWith("go-search-jni-qa-"))
            if (model.exists()) check(model.delete())
            check(model.parentFile.delete())
            ownedModel = null
        }
    }
}
