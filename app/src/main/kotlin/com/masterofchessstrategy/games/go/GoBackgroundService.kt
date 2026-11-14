package com.masterofchessstrategy.games.go

import android.app.Notification
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.R
import com.masterofchessstrategy.engine.GoPhase
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal data class GoBackgroundLease(
    val token: String,
    val owner: GoAiMatchViewModel,
    val matchId: String,
    val returnTo: ComponentName?,
    val visible: Boolean = true,
    val ready: Boolean = false,
    val eventCount: Int,
)

/** Runs on the main dispatcher and grants execution to the existing match owner. */
@MainThread
internal object GoBackgroundPlayback {
    const val CHANNEL = "go_auto_runtime"
    const val TOKEN = "go_auto_runtime_token"
    private val mutableLease = MutableStateFlow<GoBackgroundLease?>(null)
    val lease = mutableLease.asStateFlow()
    private var releaseActivityObserver: (() -> Unit)? = null

    fun start(activity: Activity, owner: GoAiMatchViewModel, match: GoAiMatchUiState): Boolean {
        if (mutableLease.value != null || !match.canStartBackground || !notificationsAllowed(activity)) return false
        val entry = GoBackgroundLease(UUID.randomUUID().toString(), owner, match.matchId, activity.componentName,
            eventCount = match.eventCount)
        mutableLease.value = entry
        observeActivity(activity, entry)
        return try {
            ContextCompat.startForegroundService(activity, Intent(activity, GoBackgroundService::class.java)
                .putExtra(TOKEN, entry.token))
            true
        } catch (_: RuntimeException) {
            clearLease()
            false
        }
    }

    /** A configuration change can resume the Activity before Navigation restores its destination. */
    private fun observeActivity(activity: Activity, entry: GoBackgroundLease) {
        val application = activity.application
        val task = activity.taskId
        val component = activity.componentName
        val observer = object : Application.ActivityLifecycleCallbacks {
            private fun owns(candidate: Activity) = candidate.taskId == task && candidate.componentName == component &&
                mutableLease.value?.token == entry.token

            override fun onActivityStarted(activity: Activity) {
                if (owns(activity)) entry.owner.setPlaybackVisible(activity.getSystemService(PowerManager::class.java).isInteractive)
            }

            override fun onActivityStopped(activity: Activity) {
                if (owns(activity)) entry.owner.setPlaybackVisible(false)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(observer)
        releaseActivityObserver = { application.unregisterActivityLifecycleCallbacks(observer) }
    }

    fun setVisible(owner: GoAiMatchViewModel, visible: Boolean): Boolean {
        val current = mutableLease.value?.takeIf { it.owner === owner } ?: return false
        mutableLease.value = current.copy(visible = visible)
        return current.ready
    }

    fun update(owner: GoAiMatchViewModel, match: GoAiMatchUiState) {
        val current = mutableLease.value?.takeIf { it.owner === owner } ?: return
        if (current.matchId != match.matchId || !match.supportsBackground) {
            // Foreground dead-stone review keeps the series' existing continuation intent.
            if (current.visible && match.game.phase == GoPhase.SCORING) clearLease()
            else stop(current.token)
        } else if (current.eventCount != match.eventCount) {
            mutableLease.value = current.copy(eventCount = match.eventCount)
        }
    }

    fun acknowledge(token: String): Boolean {
        val current = mutableLease.value?.takeIf { it.token == token && it.visible } ?: return false
        mutableLease.value = current.copy(ready = true)
        return true
    }

    fun stopOwner(owner: GoAiMatchViewModel) {
        mutableLease.value?.takeIf { it.owner === owner }?.let { stop(it.token) }
    }

    fun stop(token: String) {
        val current = mutableLease.value?.takeIf { it.token == token } ?: return
        clearLease()
        current.owner.pause()
    }

    private fun clearLease() {
        mutableLease.value = null
        releaseActivityObserver?.invoke()
        releaseActivityObserver = null
    }

    fun notificationsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 26) return manager.areNotificationsEnabled()
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "围棋后台演局", NotificationManager.IMPORTANCE_LOW))
        }
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
}

private val GoAiMatchUiState.supportsBackground get() = config.automatic && game.sessionReady && !closed &&
    !paused && game.phase == GoPhase.PLAYING && game.error == null

internal val GoAiMatchUiState.canStartBackground get() = supportsBackground && game.canOperate && matchId.isNotBlank()

/** Keeps only the user-started match awake. Service restart never creates a match owner. */
class GoBackgroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var token: String? = null
    private var watcher: Job? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val current = GoBackgroundPlayback.lease.value?.takeIf { it.token == token } ?: return
            if (intent.action == Intent.ACTION_SCREEN_OFF) current.owner.setPlaybackVisible(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "MasterofChessStrategy:GoBackground").apply { setReferenceCounted(false) }
        ContextCompat.registerReceiver(this, screenEvents, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(GoBackgroundPlayback.TOKEN)
        if (intent?.action == STOP) {
            requested?.let(GoBackgroundPlayback::stop)
            if (token == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (requested == null) {
            if (token == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val current = GoBackgroundPlayback.lease.value
        try {
            val notice = notification(current?.token ?: requested, current?.returnTo)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notice)
        } catch (_: RuntimeException) {
            GoBackgroundPlayback.stop(requested)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (GoBackgroundPlayback.acknowledge(requested)) {
            token = requested
            watcher?.cancel()
            watcher = scope.launch {
                GoBackgroundPlayback.lease.collectLatest { lease ->
                    if (lease == null) stopSelf()
                    else if (lease.token == requested && !lease.visible) watchPlayback(lease)
                }
            }
        } else {
            GoBackgroundPlayback.stop(requested)
            if (GoBackgroundPlayback.lease.value == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun watchPlayback(lease: GoBackgroundLease) {
        try {
            val deadline = SystemClock.elapsedRealtime() + PROGRESS_TIMEOUT_MILLIS
            try { wakeLock.acquire(PROGRESS_TIMEOUT_MILLIS) }
            catch (_: RuntimeException) { GoBackgroundPlayback.stop(lease.token); return }
            while (GoBackgroundPlayback.lease.value == lease) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0 || !GoBackgroundPlayback.notificationsAllowed(this)) {
                    GoBackgroundPlayback.stop(lease.token)
                    return
                }
                delay(minOf(remaining, 2_000))
            }
        } finally { releaseWakeLock() }
    }

    private fun notification(token: String, returnTo: ComponentName?): Notification {
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java)
            .setComponent(returnTo ?: ComponentName(this, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, NOTIFICATION_ID, Intent(this, javaClass).setAction(STOP)
            .setData("mocs-go://stop/$token".toUri()).putExtra(GoBackgroundPlayback.TOKEN, token), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, GoBackgroundPlayback.CHANNEL)
            .setSmallIcon(R.drawable.ic_chess_notification).setContentTitle("围棋后台演局")
            .setContentText("返回棋盘查看进度，或停止后台演局。")
            .setSilent(true).setOnlyAlertOnce(true).setOngoing(true).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).addAction(0, "停止后台演局", stop).build()
    }

    private fun releaseWakeLock() { if (wakeLock.isHeld) wakeLock.release() }
    override fun onTaskRemoved(rootIntent: Intent?) { token?.let(GoBackgroundPlayback::stop); stopSelf() }
    override fun onTimeout(startId: Int, fgsType: Int) { token?.let(GoBackgroundPlayback::stop); stopSelf(startId) }
    override fun onDestroy() {
        unregisterReceiver(screenEvents)
        scope.cancel()
        releaseWakeLock()
        token?.let(GoBackgroundPlayback::stop)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val NOTIFICATION_ID = 2403
        const val PROGRESS_TIMEOUT_MILLIS = 45_000L
        const val STOP = "com.masterofchessstrategy.STOP_GO_BACKGROUND"
    }
}
