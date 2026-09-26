package com.masterofchessstrategy.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.masterofchessstrategy.R
import com.masterofchessstrategy.game.BackgroundRunStatus
import com.masterofchessstrategy.game.ChineseChessBackgroundController

/** Permission is requested only after an explicit user action, never on entering a game. */
@Composable
internal fun ChineseChessBackgroundControls() {
    if (LocalInspectionMode.current) return
    val context = LocalContext.current
    val controller = ChineseChessBackgroundController.get(context)
    if (controller.game == null) return
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) controller.enable() else controller.refresh()
    }
    Text(stringResource(when (controller.status) {
        BackgroundRunStatus.RUNNING -> R.string.background_running
        BackgroundRunStatus.STOPPING -> R.string.background_stopping
        BackgroundRunStatus.READY -> R.string.background_ready
        BackgroundRunStatus.OFF -> R.string.background_off
        BackgroundRunStatus.NOTIFICATIONS_REQUIRED -> R.string.background_notifications_required
        BackgroundRunStatus.UNAVAILABLE -> R.string.background_unavailable
    }), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("background_status"))
    OutlinedButton(
        onClick = {
            if (controller.enabled) controller.stop()
            else if (Build.VERSION.SDK_INT >= 33 && !controller.notificationsAllowed()) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else controller.enable()
        },
        enabled = controller.status != BackgroundRunStatus.STOPPING,
        modifier = Modifier.testTag("background_toggle"),
    ) { Text(stringResource(if (controller.enabled) R.string.background_stop else R.string.background_enable)) }
    if (controller.status == BackgroundRunStatus.NOTIFICATIONS_REQUIRED) {
        OutlinedButton(onClick = {
            val intent = if (Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
            }
            context.startActivity(intent)
        }) { Text(stringResource(R.string.background_system_settings)) }
    }
}
