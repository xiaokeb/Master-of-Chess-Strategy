package com.masterofchessstrategy.games.go

import android.content.Context
import com.masterofchessstrategy.engine.GoSearchRuntime
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Opens the locked offline asset, then transfers a verified Native runtime to the page owner.
 * The runtime factory must validate internal model identity and own its JNI registration.
 */
class GoAiAssetModelLoader internal constructor(
    private val prepareModel: (() -> Boolean) -> File,
    private val runtimeFactory: (File) -> GoSearchRuntime,
    private val worker: CoroutineDispatcher,
) {
    constructor(
        context: Context,
        runtimeFactory: (File) -> GoSearchRuntime,
        worker: CoroutineDispatcher = Dispatchers.Default,
    ) : this(assetPreparation(context), runtimeFactory, worker)

    suspend fun load(): GoAiMatchEngineOwner {
        var candidate: GoSearchRuntime? = null
        try {
            val owner = withContext(worker) {
                val task = currentCoroutineContext()
                task.ensureActive()
                val model = prepareModel { !task.isActive }
                task.ensureActive()
                candidate = runtimeFactory(model)
                task.ensureActive()
                GoAiRuntimeOwnerAdapter(checkNotNull(candidate), worker)
            }
            candidate = null
            return owner
        } catch (failure: Throwable) {
            // A synchronous constructor can finish after caller cancellation. Join/close it here.
            withContext(worker + NonCancellable) {
                try { candidate?.close() }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    companion object {
        const val MODEL_ASSET_DIRECTORY = "games/go/ai"
        // Android packaging treats .gz as compressible input and may expose decompressed bytes.
        // The neutral final suffix preserves the locked 48,038,914-byte gzip stream verbatim.
        const val MODEL_ASSET_FILE_NAME = "kata1-b15c192-s1672170752-d466197061.txt.gz.bin"
        const val MODEL_ASSET_PATH = "$MODEL_ASSET_DIRECTORY/$MODEL_ASSET_FILE_NAME"

        private fun assetPreparation(context: Context): ((() -> Boolean) -> File) {
            val application = context.applicationContext
            return { cancelled ->
                GoAiModelFiles(File(application.noBackupFilesDir, "go-ai-v1")).prepare(
                    sourceAsset = {
                        application.assets.open(MODEL_ASSET_PATH)
                    },
                    cancelled = cancelled,
                )
            }
        }
    }
}
