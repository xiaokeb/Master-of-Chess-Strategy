package com.masterofchessstrategy.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.data.AppSettingsRepository
import com.masterofchessstrategy.data.HighlightCondition
import com.masterofchessstrategy.data.LoadAppSettingsResult
import com.masterofchessstrategy.engine.Difficulty
import kotlinx.coroutines.launch

internal enum class SettingsFeedback {
    SAVED,
    LOAD_RECOVERED,
    SAVE_FAILED,
}

internal data class AppSettingsUiState(
    val settings: AppSettings = AppSettings.DEFAULT,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val feedback: SettingsFeedback? = null,
) {
    val isInteractionEnabled: Boolean
        get() = !isLoading && !isSaving
}

/**
 * Serializes whole-row settings writes by disabling edits during each commit.
 */
internal class AppSettingsViewModel(
    private val repository: AppSettingsRepository,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    var uiState by mutableStateOf(AppSettingsUiState())
        private set

    init {
        loadSettings()
    }

    fun setDefaultDifficulty(difficulty: Difficulty) {
        update { copy(defaultDifficulty = difficulty) }
    }

    fun setAutoContinue(enabled: Boolean) {
        update { copy(autoContinueEnabled = enabled) }
    }

    fun adjustAutoContinueLimit(deltaGames: Int) {
        if (deltaGames == 0) return
        update {
            copy(
                autoContinueGameLimit = (autoContinueGameLimit + deltaGames)
                    .coerceIn(AppSettings.AUTO_CONTINUE_LIMIT_RANGE),
            )
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        update { copy(soundEnabled = enabled) }
    }

    fun setAiFirstEnabled(enabled: Boolean) {
        update { copy(aiFirstEnabled = enabled) }
    }

    fun setHighlightCondition(condition: HighlightCondition, enabled: Boolean) {
        update {
            copy(
                highlightConditionsMask = if (enabled) {
                    highlightConditionsMask or condition.bit
                } else {
                    highlightConditionsMask and condition.bit.inv()
                },
            )
        }
    }

    fun setSelectedAppearance(code: Int) {
        if (code !in AppSettings.APPEARANCE_CODE_RANGE) return
        update { copy(selectedAppearanceCode = code) }
    }

    fun setTimeLimitEnabled(enabled: Boolean) {
        update {
            copy(gameDurationMinutes = if (enabled) DEFAULT_DURATION_MINUTES else null)
        }
    }

    fun adjustDuration(deltaMinutes: Int) {
        if (deltaMinutes == 0) return
        update {
            val current = gameDurationMinutes ?: DEFAULT_DURATION_MINUTES
            copy(
                gameDurationMinutes = (current + deltaMinutes)
                    .coerceIn(AppSettings.DURATION_RANGE),
            )
        }
    }

    fun dismissFeedback() {
        uiState = uiState.copy(feedback = null)
    }

    private fun update(transform: AppSettings.() -> AppSettings) {
        if (!uiState.isInteractionEnabled) return
        val previous = uiState.settings
        val updated = previous.transform().copy(updatedAtEpochMillis = nowEpochMillis())
        uiState = uiState.copy(
            settings = updated,
            isSaving = true,
            feedback = null,
        )
        viewModelScope.launch {
            try {
                repository.save(updated)
                uiState = uiState.copy(
                    isSaving = false,
                    feedback = SettingsFeedback.SAVED,
                )
            } catch (_: RuntimeException) {
                uiState = uiState.copy(
                    settings = previous,
                    isSaving = false,
                    feedback = SettingsFeedback.SAVE_FAILED,
                )
            }
        }
    }

    internal fun loadSettings() {
        uiState = AppSettingsUiState(isLoading = true)
        viewModelScope.launch {
            val result = try {
                repository.load()
            } catch (_: RuntimeException) {
                uiState = AppSettingsUiState(
                    isLoading = false,
                    feedback = SettingsFeedback.LOAD_RECOVERED,
                )
                return@launch
            }
            when (result) {
                LoadAppSettingsResult.NotFound -> {
                    uiState = AppSettingsUiState(isLoading = false)
                }

                LoadAppSettingsResult.Incompatible -> {
                    clearIncompatibleSettings()
                    uiState = AppSettingsUiState(
                        isLoading = false,
                        feedback = SettingsFeedback.LOAD_RECOVERED,
                    )
                }

                is LoadAppSettingsResult.Loaded -> {
                    uiState = AppSettingsUiState(
                        settings = result.settings,
                        isLoading = false,
                    )
                }
            }
        }
    }

    private suspend fun clearIncompatibleSettings() {
        try {
            repository.clear()
        } catch (_: RuntimeException) {
            // Invalid values remain hidden behind in-memory safe defaults.
        }
    }

    companion object {
        const val DEFAULT_DURATION_MINUTES = 30
        const val DURATION_STEP_MINUTES = 5
        const val AUTO_CONTINUE_LIMIT_STEP = 1

        fun factory(repository: AppSettingsRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AppSettingsViewModel(repository)
                }
            }
    }
}
