package com.masterofchessstrategy.games.go

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.LocalDataWriterFailure
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Real locked model, JNI searches and rules. Only delivery/close timing and one post-close
 * exception are injected; the checkpoint store is memory-only and no user database is opened.
 */
class GoAiNativeOwnerRetirementInstrumentedTest {
    @Test fun retirementDuringRealModelDeliveryWaitsForLateRuntimeReleaseBeforeCompletion() = runBlocking {
        withOwnedModel { files, events ->
            val runtime = AtomicReference<TrackingRuntime>()
            val ready = CompletableDeferred<Unit>()
            val deliver = CompletableDeferred<Unit>()
            val repository = MemoryRepository()
            val store = ViewModelStore()
            var model: GoAiMatchViewModel? = null
            try {
                val loader = loader(files, events, runtime)
                val current = withContext(Dispatchers.Main.immediate) {
                    GoAiMatchViewModel(repository, GoAiMatchConfig(9), {
                        val owned = loader.load()
                        ready.complete(Unit)
                        deliver.await() // Timing injection after actual Native construction.
                        owned
                    }).also { model = it; store.put("qa", it) }
                }
                withTimeout(90_000) { ready.await() }
                val completion = withContext(Dispatchers.Main.immediate) {
                    current.beginRetirement().also { it.invokeOnCompletion { events += "retirement-complete" } }
                }
                assertFalse(completion.isCompleted)
                assertEquals(0, repository.saves.get())
                deliver.complete(Unit)
                withTimeout(90_000) { completion.await() }
                assertEquals(0, repository.saves.get())
                assertNull(current.state.value.match)
                assertBefore(events, "runtime-closed", "retirement-complete")
                assertRuntimeClosed(runtime.get())
            } finally {
                deliver.complete(Unit)
                withContext(Dispatchers.Main.immediate) {
                    model?.beginRetirement()?.await()
                    store.clear()
                }
            }
        }
    }

    @Test fun observedNativeSearchRetirementJoinsRequestThenRulesThenModelBeforeStableCompletion() = runBlocking {
        withOwnedModel { files, events ->
            val runtime = AtomicReference<TrackingRuntime>()
            val releaseRequest = CountDownLatch(1)
            val repository = MemoryRepository()
            val store = ViewModelStore()
            var model: GoAiMatchViewModel? = null
            try {
                val loader = loader(files, events, runtime, releaseRequest)
                val current = withContext(Dispatchers.Main.immediate) {
                    GoAiMatchViewModel(repository, GoAiMatchConfig(19, humanPlayer = null, difficulty = GoAiDifficulty.MASTER),
                        loader::load, engineFactory = rulesFactory(events)).also { model = it; store.put("qa", it) }
                }
                withTimeout(90_000) { current.state.first { it.match?.game?.canOperate == true } }
                withContext(Dispatchers.Main.immediate) { current.resume() }
                awaitActualNativeCall(runtime.get())
                val completion = withContext(Dispatchers.Main.immediate) {
                    current.beginRetirement().also { it.invokeOnCompletion { events += "retirement-complete" } }
                }
                withTimeout(90_000) {
                    while (!runtime.get().requestClosed.isCompleted) delay(10)
                }
                assertFalse(completion.isCompleted) // Actual request closed, final wrapper release is deliberately gated.
                assertFalse(events.contains("rules-closed"))
                assertFalse(events.contains("runtime-closed"))
                releaseRequest.countDown()
                withTimeout(90_000) { completion.await() }
                assertBefore(events, "request-closed", "rules-closed")
                assertBefore(events, "rules-closed", "runtime-closed")
                assertBefore(events, "runtime-closed", "retirement-complete")
                assertTrue(current.state.value.match!!.closed)
                assertSame(completion, withContext(Dispatchers.Main.immediate) { current.beginRetirement() })
                val savesAfterClose = repository.saves.get()
                withContext(Dispatchers.Main.immediate) { current.resume(); current.retrySearch(); current.retryModel() }
                assertEquals(savesAfterClose, repository.saves.get())
                assertRuntimeClosed(runtime.get())
            } finally {
                releaseRequest.countDown()
                withContext(Dispatchers.Main.immediate) {
                    model?.beginRetirement()?.await()
                    store.clear()
                }
            }
        }
    }

    @Test fun errorAfterRealRulesReleaseStillClosesNativeModelAndBlocksTheBackupBoundary() = runBlocking {
        withOwnedModel { files, events ->
            val runtime = AtomicReference<TrackingRuntime>()
            val failure = IOException("owned QA failure after real rules close")
            val registry = LocalDataWriterRegistry()
            val store = ViewModelStore()
            var model: GoAiMatchViewModel? = null
            try {
                val loader = loader(files, events, runtime)
                val current = withContext(Dispatchers.Main.immediate) {
                    GoAiMatchViewModel(MemoryRepository(), GoAiMatchConfig(9), loader::load,
                        engineFactory = rulesFactory(events, failure)).also {
                        model = it; store.put("qa", it); registry.register(it)
                    }
                }
                withTimeout(90_000) { current.state.first { it.match?.game?.canOperate == true } }
                val completion = withContext(Dispatchers.Main.immediate) { current.beginRetirement() }
                try { withTimeout(90_000) { completion.await() }; fail("Cleanup failure hidden") }
                catch (actual: IOException) { assertSame(failure, actual) }
                assertTrue(completion.isCancelled)
                assertBefore(events, "rules-closed", "runtime-closed")
                assertRuntimeClosed(runtime.get())
                var backupEntered = false
                withContext(Dispatchers.Main.immediate) {
                    store.clear()
                    assertTrue(current in registry.snapshot())
                    try {
                        awaitLocalDataWriters(registry.snapshot()) {}
                        backupEntered = true // Sentinel, not a Room/SAF backup operation.
                    } catch (actual: IOException) { assertSame(failure, actual) }
                }
                assertFalse(backupEntered)
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    try { model?.beginRetirement()?.await() } catch (_: IOException) { }
                    store.clear()
                }
            }
        }
    }

    @Test fun failuresAfterBothRealNativeReleasesRemainObservableAndBlockBackup() = runBlocking {
        withOwnedModel { files, events ->
            val runtime = AtomicReference<TrackingRuntime>()
            val rulesFailure = IOException("owned QA fault after real rules release")
            val modelFailure = IllegalStateException("owned QA fault after real model release")
            val registry = LocalDataWriterRegistry()
            val store = ViewModelStore()
            var model: GoAiMatchViewModel? = null
            try {
                val actualLoader = loader(files, events, runtime)
                val current = withContext(Dispatchers.Main.immediate) {
                    GoAiMatchViewModel(MemoryRepository(), GoAiMatchConfig(9), {
                        val actualOwner = actualLoader.load()
                        object : GoAiMatchEngineOwner by actualOwner {
                            override suspend fun closeAndJoin() {
                                actualOwner.closeAndJoin() // Fault only after the real runtime closed.
                                throw modelFailure
                            }
                        }
                    }, engineFactory = rulesFactory(events, rulesFailure)).also {
                        model = it; store.put("qa", it); registry.register(it)
                    }
                }
                withTimeout(90_000) { current.state.first { it.match?.game?.canOperate == true } }
                val completion = withContext(Dispatchers.Main.immediate) { current.beginRetirement() }
                val failure = try {
                    withTimeout(90_000) { completion.await() }
                    error("Both cleanup errors were hidden")
                } catch (actual: LocalDataWriterFailure) { actual }
                assertEquals(2, failure.failures.size)
                assertTrue(generateSequence(failure.cause) { it.cause }.any { it === rulesFailure })
                assertTrue(generateSequence(failure.failures[1]) { it.cause }.any { it === modelFailure })
                assertSame(failure.failures[1], failure.suppressed.single())
                assertTrue(completion.isCancelled)
                assertBefore(events, "rules-closed", "runtime-closed")
                assertRuntimeClosed(runtime.get())
                var backupEntered = false
                withContext(Dispatchers.Main.immediate) {
                    assertSame(completion, current.beginRetirement())
                    store.clear()
                    assertTrue(current in registry.snapshot())
                    try {
                        awaitLocalDataWriters(registry.snapshot()) {}
                        backupEntered = true
                    } catch (actual: LocalDataWriterFailure) {
                        assertSame(failure, actual)
                        assertEquals(2, actual.failures.size)
                    }
                }
                assertFalse(backupEntered)
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    try { model?.beginRetirement()?.await() } catch (_: LocalDataWriterFailure) { }
                    store.clear()
                }
            }
        }
    }

    private fun loader(files: GoAiModelFiles, events: MutableList<String>, runtime: AtomicReference<TrackingRuntime>,
        releaseRequest: CountDownLatch? = null): GoAiAssetModelLoader {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return GoAiAssetModelLoader({ cancelled ->
            files.prepare({ context.assets.open(GoAiAssetModelLoader.MODEL_ASSET_PATH) }, cancelled)
        }, { file ->
            TrackingRuntime(NativeGoSearchRuntime(file.absolutePath), events, releaseRequest).also(runtime::set)
        }, Dispatchers.Default)
    }

    private fun rulesFactory(events: MutableList<String>, failure: IOException? = null): (Int, Int, Int) -> GoRecordEngine =
        { size, komi, handicap ->
            val actual = NativeGoEngine(size, komi, handicap)
            object : GoRecordEngine by actual {
                override fun close() {
                    actual.close()
                    events += "rules-closed"
                    failure?.let { throw it } // Fault injection only after the real release succeeded.
                }
            }
        }

    private suspend fun awaitActualNativeCall(runtime: TrackingRuntime) = withTimeout(30_000) {
        while (true) {
            val thread = runtime.searchThread.get()
            if (thread != null && thread.stackTrace.any {
                    it.isNativeMethod && it.className == "com.masterofchessstrategy.engine.internal.GoSearchBindings" && it.methodName == "run"
                }) return@withTimeout
            check(!runtime.runFinished.isCompleted) { "Search returned before an actual JNI run frame was observed" }
            delay(5)
        }
    }

    private fun assertRuntimeClosed(runtime: TrackingRuntime) {
        val bytes = NativeGoEngine(9).use { it.serialize() }
        try { runtime.actual.prepare(bytes, GoSearchBudget(1, 100)).close(); fail("Real runtime admission stayed open") }
        catch (_: IllegalStateException) { }
    }

    private fun assertBefore(events: MutableList<String>, first: String, second: String) {
        val copy = synchronized(events) { events.toList() }
        assertTrue("Missing or reversed $first/$second in $copy", copy.indexOf(first) >= 0 && copy.indexOf(second) > copy.indexOf(first))
    }

    private suspend fun withOwnedModel(block: suspend (GoAiModelFiles, MutableList<String>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "go-ai-owner-retirement-${UUID.randomUUID()}")
        check(directory.mkdir())
        try { block(GoAiModelFiles(directory), Collections.synchronizedList(mutableListOf())) }
        finally {
            val permitted = GoAiModelSpec.OFFICIAL_15B.fileName
            directory.listFiles()!!.forEach { file ->
                check(file.name in setOf(permitted, "$permitted.bak", "$permitted.new") && file.isFile)
                check(file.delete())
            }
            check(directory.delete())
        }
    }

    private class MemoryRepository : GoAiMatchRepository {
        @Volatile private var saved: GoAiSavedMatch? = null
        val saves = AtomicInteger()
        override suspend fun load() = saved
        override suspend fun save(match: GoAiSavedMatch) { saved = match; saves.incrementAndGet() }
    }

    private class TrackingRuntime(val actual: NativeGoSearchRuntime, private val events: MutableList<String>,
        private val releaseRequest: CountDownLatch?) : GoSearchRuntime {
        val searchThread = AtomicReference<Thread>()
        val runFinished = CompletableDeferred<Unit>()
        val requestClosed = CompletableDeferred<Unit>()
        override fun prepare(snapshot: ByteArray, budget: GoSearchBudget): GoSearchRequest {
            val request = actual.prepare(snapshot, budget)
            return object : GoSearchRequest {
                override fun run(): GoSearchResult {
                    searchThread.set(Thread.currentThread())
                    try { return request.run() } finally { runFinished.complete(Unit) }
                }
                override fun cancel() = request.cancel()
                override fun close() {
                    request.close()
                    events += "request-closed"
                    requestClosed.complete(Unit)
                    check(releaseRequest?.await(60, TimeUnit.SECONDS) != false) { "Owned QA close gate timed out" }
                }
            }
        }
        override fun cancelAll() = actual.cancelAll()
        override fun close() { actual.close(); events += "runtime-closed" }
    }
}
