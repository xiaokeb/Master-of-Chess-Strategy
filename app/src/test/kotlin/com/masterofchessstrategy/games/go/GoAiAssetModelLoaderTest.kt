package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoAiAssetModelLoaderTest {
    private val model = File("verified-model.txt.gz")

    @Test fun assetCarrierSuffixPreservesCompressedBytesWhileCacheKeepsOfficialIdentity() {
        assertEquals(GoAiModelSpec.OFFICIAL_15B.fileName + ".bin", GoAiAssetModelLoader.MODEL_ASSET_FILE_NAME)
        assertEquals("games/go/ai/kata1-b15c192-s1672170752-d466197061.txt.gz.bin",
            GoAiAssetModelLoader.MODEL_ASSET_PATH)
        assertTrue(GoAiModelSpec.OFFICIAL_15B.fileName.endsWith(".txt.gz"))
        assertFalse(GoAiAssetModelLoader.MODEL_ASSET_FILE_NAME.endsWith(".gz"))
    }

    @Test fun verifiesBeforeOpeningAndTransfersRuntimeOwnership() = runTest {
        val calls = mutableListOf<String>()
        val runtime = Runtime()
        val loader = GoAiAssetModelLoader({ cancelled ->
            assertFalse(cancelled()); calls += "verified"; model
        }, { file -> assertSame(model, file); calls += "native"; runtime }, StandardTestDispatcher(testScheduler))
        val owner = loader.load()
        assertEquals(listOf("verified", "native"), calls)
        assertEquals(0, runtime.closes)
        owner.closeAndJoin()
        owner.closeAndJoin()
        assertEquals(1, runtime.closes)
    }

    @Test fun verificationFailureNeverOpensRuntime() = runTest {
        var opened = false
        val loader = GoAiAssetModelLoader({ throw IOException("bad hash") }, {
            opened = true; Runtime()
        }, StandardTestDispatcher(testScheduler))
        try { loader.load(); fail("Integrity failure hidden") } catch (_: IOException) { }
        assertFalse(opened)
    }

    @Test fun cancellationDuringVerificationPreventsNativeConstruction() = runTest {
        val job = Job()
        var opened = false
        val loader = GoAiAssetModelLoader({ cancelled ->
            job.cancel(); assertTrue(cancelled()); model
        }, { opened = true; Runtime() }, StandardTestDispatcher(testScheduler))
        val loading = CoroutineScope(job + StandardTestDispatcher(testScheduler)).async { loader.load() }
        runCurrent()
        try { loading.await(); fail("Cancellation ignored") } catch (_: CancellationException) { }
        assertFalse(opened)
    }

    @Test fun cancellationDuringSynchronousConstructionClosesLateRuntime() = runTest {
        val job = Job()
        val runtime = Runtime()
        val loader = GoAiAssetModelLoader({ model }, { job.cancel(); runtime }, StandardTestDispatcher(testScheduler))
        val loading = CoroutineScope(job + StandardTestDispatcher(testScheduler)).async { loader.load() }
        runCurrent()
        try { loading.await(); fail("Late runtime escaped") } catch (_: CancellationException) { }
        assertEquals(1, runtime.closes)
    }

    @Test fun dispatcherReturnCancellationClosesRuntimeBeforeDelivery() = runTest {
        val ownerDispatcher = StandardTestDispatcher(testScheduler)
        val worker = StandardTestDispatcher(testScheduler)
        val job = Job()
        val runtime = Runtime()
        // Constructor schedules cancellation on the caller before withContext's return dispatch.
        runtime.prepared = { error("No search should start while loading") }
        val wrapped = GoAiAssetModelLoader({ model }, {
            CoroutineScope(job + ownerDispatcher).launch { job.cancel() }
            runtime
        }, worker)
        val loading = CoroutineScope(job + ownerDispatcher).async { wrapped.load() }
        runCurrent()
        try { loading.await(); fail("Cancelled return was delivered") } catch (_: CancellationException) { }
        assertEquals(1, runtime.closes)
    }

    @Test fun nativeFailurePreservedAndRetryCanCreateFreshOwner() = runTest {
        var count = 0
        val runtime = Runtime()
        val loader = GoAiAssetModelLoader({ model }, {
            if (++count == 1) throw IOException("native model rejected")
            runtime
        }, StandardTestDispatcher(testScheduler))
        try { loader.load(); fail("Native failure hidden") } catch (_: IOException) { }
        val owner = loader.load()
        assertEquals(2, count)
        owner.closeAndJoin()
        assertEquals(1, runtime.closes)
    }

    private class Runtime : GoSearchRuntime {
        var closes = 0
        var prepared: () -> Unit = { error("Search was not requested") }
        override fun prepare(snapshot: ByteArray, budget: GoSearchBudget): GoSearchRequest {
            prepared(); error("Unused")
        }
        override fun cancelAll() = Unit
        override fun close() { closes++ }
    }
}
