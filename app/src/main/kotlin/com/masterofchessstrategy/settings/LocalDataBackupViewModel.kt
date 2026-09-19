package com.masterofchessstrategy.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.LocalDataBackupCodec
import com.masterofchessstrategy.data.LocalDataBackupRepository
import com.masterofchessstrategy.data.LocalDataRestoreSummary
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class BackupFeedback {
    EXPORTED,
    RESTORED,
    EXPORT_FAILED,
    RESTORE_FAILED,
}

internal data class LocalDataBackupUiState(
    val isWorking: Boolean = false,
    val feedback: BackupFeedback? = null,
    val restoreSummary: LocalDataRestoreSummary? = null,
)

/** Coordinates user-selected document streams without retaining Android URI permissions. */
internal class LocalDataBackupViewModel(
    private val repository: LocalDataBackupRepository,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    var uiState = LocalDataBackupUiState()
        private set

    fun export(openOutputStream: () -> OutputStream?) {
        if (uiState.isWorking) return
        uiState = LocalDataBackupUiState(isWorking = true)
        viewModelScope.launch {
            val succeeded = try {
                withContext(ioDispatcher) {
                    val bytes = repository.export(nowEpochMillis())
                    require(bytes.size <= LocalDataBackupCodec.MAX_BACKUP_BYTES)
                    checkNotNull(openOutputStream()).use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                }
                true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                false
            }
            uiState = LocalDataBackupUiState(
                feedback = if (succeeded) {
                    BackupFeedback.EXPORTED
                } else {
                    BackupFeedback.EXPORT_FAILED
                },
            )
        }
    }

    fun restore(openInputStream: () -> InputStream?, onRestored: () -> Unit) {
        if (uiState.isWorking) return
        uiState = LocalDataBackupUiState(isWorking = true)
        viewModelScope.launch {
            val summary = try {
                withContext(ioDispatcher) {
                    val bytes = checkNotNull(openInputStream()).use { input ->
                        input.readBounded()
                    }
                    repository.restore(bytes)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
            uiState = if (summary == null) {
                LocalDataBackupUiState(feedback = BackupFeedback.RESTORE_FAILED)
            } else {
                LocalDataBackupUiState(
                    feedback = BackupFeedback.RESTORED,
                    restoreSummary = summary,
                )
            }
            if (summary != null) onRestored()
        }
    }

    fun dismissFeedback() {
        uiState = uiState.copy(feedback = null)
    }

    private fun InputStream.readBounded(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= LocalDataBackupCodec.MAX_BACKUP_BYTES) {
                "Backup exceeds the supported size"
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray().also { require(it.isNotEmpty()) }
    }

    companion object {
        fun factory(repository: LocalDataBackupRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { LocalDataBackupViewModel(repository) }
            }
    }
}
