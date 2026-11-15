package com.masterofchessstrategy.games.go

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Keeps epoch-based saved deadlines stable within the current Android boot. */
internal class GoElapsedClock(
    private val elapsedMillis: () -> Long,
    wallMillis: () -> Long,
    readOffset: () -> Long?,
    writeOffset: (Long) -> Unit,
) {
    private val offset by lazy { readOffset() ?: (wallMillis() - elapsedMillis()).also(writeOffset) }

    fun now(): Long = (offset + elapsedMillis()).coerceAtLeast(0)
}

internal object GoAndroidClock {
    @Volatile private var instance: GoElapsedClock? = null

    fun source(context: Context): () -> Long {
        val application = context.applicationContext
        return { clock(application).now() }
    }

    private fun clock(context: Context): GoElapsedClock =
        instance ?: synchronized(this) {
            instance ?: create(context).also { instance = it }
        }

    private fun create(context: Context): GoElapsedClock {
        val boot = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (_: SecurityException) { -1 }
        val file = AtomicFile(File(context.noBackupFilesDir, "go-clock-anchor-v1"))
        return GoElapsedClock(SystemClock::elapsedRealtime, System::currentTimeMillis,
            readOffset = {
                if (boot < 0) null else try {
                    DataInputStream(file.openRead()).use { input ->
                        if (input.readInt() != boot) null
                        else input.readLong().takeIf { input.read() == -1 }
                    }
                } catch (_: IOException) { null }
            },
            writeOffset = { offset ->
                if (boot >= 0) persist(file, boot, offset)
            })
    }

    private fun persist(file: AtomicFile, boot: Int, offset: Long) {
        val stream = try { file.startWrite() } catch (_: IOException) { return }
        try {
            stream.write(ByteBuffer.allocate(Int.SIZE_BYTES + Long.SIZE_BYTES).putInt(boot).putLong(offset).array())
            file.finishWrite(stream)
        } catch (_: IOException) {
            file.failWrite(stream)
        }
    }
}
