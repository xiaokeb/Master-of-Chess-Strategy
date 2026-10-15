package com.masterofchessstrategy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.R
/** Render the host's registered modes; absent games do not create dead navigation entries. */
@Composable
internal fun LanzhouTraditionalModesScreen(
    menu: TraditionalGameMenu,
    onBack: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.lanzhou_traditional_choose),
                style = MaterialTheme.typography.headlineMedium)
            menu.entries.forEach { entry ->
                key(entry.id) {
                    val modifier = Modifier.fillMaxWidth().testTag(entry.testTag)
                    when (entry.style) {
                        TraditionalMenuButtonStyle.FILLED -> Button(onClick = entry.onClick, modifier = modifier) {
                            Text(entry.label)
                        }
                        TraditionalMenuButtonStyle.OUTLINED -> OutlinedButton(onClick = entry.onClick, modifier = modifier) {
                            Text(entry.label)
                        }
                    }
                }
            }
            OutlinedButton(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
        }
    }
}
