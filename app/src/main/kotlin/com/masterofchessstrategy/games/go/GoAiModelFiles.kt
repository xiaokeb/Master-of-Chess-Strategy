package com.masterofchessstrategy.games.go

import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** Worker-only offline extraction into the app's Go directory under noBackupFilesDir.
 * The asset provider owns no user document; model/source/notice packaging is coordinated separately.
 */
internal class GoAiModelFiles(
    directory: File,
    private val spec: GoAiModelSpec = GoAiModelSpec.OFFICIAL_15B,
) {
    private val directory = directory.canonicalFile
    private val model = File(this.directory, spec.fileName)
    private val file = AtomicFile(model)
    private val lock = directoryLocks.getOrPut(this.directory.path) { Any() }

    fun prepare(sourceAsset: () -> InputStream, cancelled: () -> Boolean = { false }): File = synchronized(lock) {
        GoAiModelIntegrity.checkCancelled(cancelled)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法创建围棋 AI 模型目录。")
        if (model.exists() || File(model.path + ".bak").exists()) {
            try {
                file.openRead().use { GoAiModelIntegrity.copyVerified(it, null, spec, cancelled) }
                return@synchronized model
            } catch (_: IOException) { /* Rebuild only this owned, invalid model cache. */ }
        }
        GoAiModelIntegrity.checkCancelled(cancelled)
        val output = file.startWrite()
        try {
            sourceAsset().use { GoAiModelIntegrity.copyVerified(it, output, spec, cancelled) }
            GoAiModelIntegrity.checkCancelled(cancelled)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        // AtomicFile may log a rename failure rather than throw; verify the published base file.
        file.openRead().use { GoAiModelIntegrity.copyVerified(it, null, spec, cancelled) }
        model
    }

    companion object {
        // Only one production Go model directory; all repository instances share its lock.
        private val directoryLocks = ConcurrentHashMap<String, Any>()
    }
}
