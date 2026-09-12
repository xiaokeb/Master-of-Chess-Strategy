package com.masterofchessstrategy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.settings.AppSettingsUiState
import com.masterofchessstrategy.settings.AppSettingsViewModel
import com.masterofchessstrategy.settings.SettingsFeedback

internal const val SETTINGS_SCREEN_TAG = "settings_screen"
internal const val SETTINGS_SOUND_TAG = "settings_sound"

@Composable
internal fun SettingsScreen(
    state: AppSettingsUiState,
    onBack: () -> Unit,
    onDefaultDifficulty: (Difficulty) -> Unit,
    onAutoContinue: (Boolean) -> Unit,
    onSoundEnabled: (Boolean) -> Unit,
    onTimeLimitEnabled: (Boolean) -> Unit,
    onAdjustDuration: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(SETTINGS_SCREEN_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PageHeader(
                title = stringResource(R.string.settings_title),
                subtitle = settingsStatusText(state),
                onBack = onBack,
            )
            DifficultySetting(state, onDefaultDifficulty)
            BooleanSettingCard(
                title = stringResource(R.string.auto_continue_title),
                summary = stringResource(R.string.auto_continue_summary),
                checked = state.settings.autoContinueEnabled,
                enabled = state.isInteractionEnabled,
                onCheckedChange = onAutoContinue,
            )
            BooleanSettingCard(
                title = stringResource(R.string.sound_title),
                summary = stringResource(R.string.sound_summary),
                checked = state.settings.soundEnabled,
                enabled = state.isInteractionEnabled,
                onCheckedChange = onSoundEnabled,
                switchTag = SETTINGS_SOUND_TAG,
            )
            DurationSetting(state, onTimeLimitEnabled, onAdjustDuration)
            Text(
                text = stringResource(R.string.settings_scope_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DifficultySetting(
    state: AppSettingsUiState,
    onDefaultDifficulty: (Difficulty) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.default_difficulty_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.default_difficulty_summary),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Difficulty.entries.forEach { difficulty ->
                    FilterChip(
                        selected = state.settings.defaultDifficulty == difficulty,
                        onClick = { onDefaultDifficulty(difficulty) },
                        enabled = state.isInteractionEnabled,
                        label = { Text(stringResource(difficulty.titleResource())) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BooleanSettingCard(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTag: String? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(text = summary, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = if (switchTag == null) {
                    Modifier
                } else {
                    Modifier.testTag(switchTag)
                },
            )
        }
    }
}

@Composable
private fun DurationSetting(
    state: AppSettingsUiState,
    onTimeLimitEnabled: (Boolean) -> Unit,
    onAdjustDuration: (Int) -> Unit,
) {
    val duration = state.settings.gameDurationMinutes
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.game_duration_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = if (duration == null) {
                            stringResource(R.string.unlimited_duration)
                        } else {
                            stringResource(R.string.duration_minutes, duration)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = duration != null,
                    onCheckedChange = onTimeLimitEnabled,
                    enabled = state.isInteractionEnabled,
                )
            }
            if (duration != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            onAdjustDuration(-AppSettingsViewModel.DURATION_STEP_MINUTES)
                        },
                        enabled = state.isInteractionEnabled &&
                            duration > AppSettings.DURATION_RANGE.first,
                    ) {
                        Text(stringResource(R.string.duration_decrease))
                    }
                    OutlinedButton(
                        onClick = {
                            onAdjustDuration(AppSettingsViewModel.DURATION_STEP_MINUTES)
                        },
                        enabled = state.isInteractionEnabled &&
                            duration < AppSettings.DURATION_RANGE.last,
                    ) {
                        Text(stringResource(R.string.duration_increase))
                    }
                }
            }
        }
    }
}

@Composable
private fun settingsStatusText(state: AppSettingsUiState): String =
    when {
        state.isLoading -> stringResource(R.string.settings_loading)
        state.isSaving -> stringResource(R.string.settings_saving)
        state.feedback == SettingsFeedback.SAVED -> stringResource(R.string.settings_saved)
        state.feedback == SettingsFeedback.LOAD_RECOVERED -> {
            stringResource(R.string.settings_load_recovered)
        }

        state.feedback == SettingsFeedback.SAVE_FAILED -> {
            stringResource(R.string.settings_save_failed)
        }

        else -> stringResource(R.string.settings_offline_summary)
    }

private fun Difficulty.titleResource(): Int =
    when (this) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
        Difficulty.MASTER -> R.string.difficulty_master
    }
