package com.masterofchessstrategy.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

internal const val APP_SAFE_CONTENT_TAG = "app_safe_content"

/** Paint behind system bars, but consume their insets once for every navigation destination. */
@Composable
internal fun MocsAppWindow(content: @Composable () -> Unit) {
    MocsTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(Modifier.fillMaxSize().testTag(APP_SAFE_CONTENT_TAG)) { content() }
            }
        }
    }
}
