package com.masterofchessstrategy.game

import android.app.NotificationChannel
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import com.masterofchessstrategy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal enum class BackgroundRunStatus { OFF, READY, RUNNING, STOPPING, NOTIFICATIONS_REQUIRED, UNAVAILABLE }
internal data class BackgroundServiceState(val token: String, val needsCpu: Boolean)

/** Main-thread registry. The service borrows this exact session; it never constructs another engine. */
internal class ChineseChessBackgroundController private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var requested = false
    private var runningServiceToken: String? = null
    private var stopping by mutableStateOf(false)
    private val mutableServiceState = MutableStateFlow<BackgroundServiceState?>(null)
    val serviceState = mutableServiceState.asStateFlow()
    var game by mutableStateOf<ChineseChessGameViewModel?>(null)
        private set
    var token: String? = null
        private set
    var enabled by mutableStateOf(false)
        private set
    var status by mutableStateOf(BackgroundRunStatus.OFF)
        private set
    var isServiceRunning by mutableStateOf(false)
        private set
    var isWakeLockHeld by mutableStateOf(false)
        private set

    init { createNotificationChannel() }

    fun attach(session: ChineseChessGameViewModel): AutoCloseable {
        // Normal navigation clears the prior VM first. This also protects unusual overlapping routes.
        game?.takeIf { it !== session }?.retireBackgroundRuntime()
        observer?.cancel()
        val leaseToken = UUID.randomUUID().toString()
        token = leaseToken
        game = session
        enabled = notificationsAllowed()
        stopping = false
        status = if (enabled) BackgroundRunStatus.READY else BackgroundRunStatus.NOTIFICATIONS_REQUIRED
        observer = scope.launch {
            snapshotFlow { Triple(session.backgroundDemand, enabled, stopping) }.collect { refresh() }
        }
        return AutoCloseable {
            if (token == leaseToken) {
                observer?.cancel()
                observer = null
                game = null
                token = null
                enabled = false
                stopping = false
                stopService()
                status = BackgroundRunStatus.OFF
            }
        }
    }

    fun enable() {
        if (game?.isRuntimeForeground != true) return
        if (!notificationsAllowed()) {
            status = BackgroundRunStatus.NOTIFICATIONS_REQUIRED
            return
        }
        enabled = true
        stopping = false
        refresh()
    }

    fun stop(expectedToken: String? = token) {
        if (expectedToken == null || expectedToken != token) return
        enabled = false
        game?.stopBackgroundAutomation()
        stopping = game?.backgroundDemand?.isBusy == true && requested
        refresh()
    }

    fun refresh() {
        val active = game ?: return
        val leaseToken = token ?: return
        if (stopping) active.stopBackgroundAutomation()
        val demand = active.backgroundDemand
        if (enabled && !notificationsAllowed()) {
            enabled = false
            active.stopBackgroundAutomation()
            stopping = demand.isBusy && requested
            status = BackgroundRunStatus.NOTIFICATIONS_REQUIRED
        }
        if (stopping && !active.backgroundDemand.isBusy) stopping = false
        val shouldRun = (enabled && active.backgroundDemand.canRun) || stopping
        if (!shouldRun) {
            stopService()
            if (status != BackgroundRunStatus.NOTIFICATIONS_REQUIRED && status != BackgroundRunStatus.UNAVAILABLE) {
                status = if (enabled) BackgroundRunStatus.READY else BackgroundRunStatus.OFF
            }
            return
        }
        mutableServiceState.value = BackgroundServiceState(leaseToken,
            active.backgroundDemand.needsCpu && !active.isRuntimeForeground)
        if (!requested) {
            // Never try to restart a service from an invisible Activity or after system termination.
            if (!active.isRuntimeForeground) { status = BackgroundRunStatus.UNAVAILABLE; return }
            try {
                requested = true
                ContextCompat.startForegroundService(context,
                    Intent(context, ChineseChessBackgroundService::class.java).putExtra(EXTRA_TOKEN, leaseToken))
            } catch (_: RuntimeException) {
                serviceFailed(leaseToken)
                return
            }
        }
        status = if (stopping) BackgroundRunStatus.STOPPING else BackgroundRunStatus.RUNNING
    }

    fun notificationsAllowed(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 26 ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE)
    }

    internal fun serviceFlags(leaseToken: String, running: Boolean, held: Boolean) {
        if (running && leaseToken == token) {
            runningServiceToken = leaseToken
            isServiceRunning = true
            isWakeLockHeld = held
        } else if (leaseToken == runningServiceToken) {
            isServiceRunning = running
            isWakeLockHeld = held
            if (!running) runningServiceToken = null
        }
    }

    internal fun serviceFailed(leaseToken: String) {
        if (leaseToken != token) return
        requested = false
        enabled = false
        stopping = false
        mutableServiceState.value = null
        status = BackgroundRunStatus.UNAVAILABLE
        game?.stopBackgroundAutomation()
    }

    internal fun serviceDestroyed(leaseToken: String) {
        serviceFlags(leaseToken, running = false, held = false)
        if (leaseToken == token && requested) serviceFailed(leaseToken)
    }

    private fun stopService() {
        mutableServiceState.value = null
        if (requested) {
            requested = false
            context.stopService(Intent(context, ChineseChessBackgroundService::class.java))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.background_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "chinese_chess_runtime"
        const val EXTRA_TOKEN = "runtime_token"
        // Only applicationContext is retained; Activity and navigation owners are never captured here.
        @SuppressLint("StaticFieldLeak")
        private var instance: ChineseChessBackgroundController? = null
        @Synchronized
        fun get(context: Context): ChineseChessBackgroundController = instance ?:
            ChineseChessBackgroundController(context.applicationContext).also { instance = it }
    }
}
