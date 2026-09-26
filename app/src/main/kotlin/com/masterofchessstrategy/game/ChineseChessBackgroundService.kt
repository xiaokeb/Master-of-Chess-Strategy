package com.masterofchessstrategy.game

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Visible keep-alive for an existing user-started game. No database, engine or restart factory here. */
class ChineseChessBackgroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: ChineseChessBackgroundController
    private var token: String? = null
    private var observer: Job? = null
    private var renewal: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        controller = ChineseChessBackgroundController.get(this)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "MasterofChessStrategy:ChineseChessRuntime",
        ).apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedToken = intent?.getStringExtra(ChineseChessBackgroundController.EXTRA_TOKEN)
        if (intent?.action == ACTION_STOP) {
            controller.stop(requestedToken)
            if (token == null || controller.serviceState.value == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val state = controller.serviceState.value
        if (state == null || requestedToken != state.token) {
            if (token == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        token = state.token
        try {
            val notification = buildNotification(state.token)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: RuntimeException) {
            controller.serviceFailed(state.token)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        controller.serviceFlags(state.token, running = true, held = false)
        observer?.cancel()
        observer = scope.launch {
            controller.serviceState.collect { current ->
                if (current == null || current.token != token) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    updateWakeLock(current.needsCpu)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(leaseToken: String): android.app.Notification {
        val returnIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, leaseToken.hashCode(),
            Intent(this, ChineseChessBackgroundService::class.java).setAction(ACTION_STOP)
                .setData("mocs-runtime://stop/$leaseToken".toUri())
                .putExtra(ChineseChessBackgroundController.EXTRA_TOKEN, leaseToken),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, ChineseChessBackgroundController.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chess_notification)
            .setContentTitle(getString(R.string.background_notification_title))
            .setContentText(getString(R.string.background_notification_body))
            .setContentIntent(returnIntent)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.background_stop), stopIntent)
            .build()
    }

    private fun updateWakeLock(required: Boolean) {
        if (!required) { releaseWakeLock(); return }
        if (renewal?.isActive == true) return
        renewal = scope.launch {
            val budget = ChineseChessWakeBudget(controller.game?.backgroundCheckpoint, SystemClock.elapsedRealtime())
            while (controller.serviceState.value?.let { it.token == token && it.needsCpu } == true) {
                try {
                    val remaining = budget.remainingMillis(controller.game?.backgroundCheckpoint, SystemClock.elapsedRealtime())
                    if (remaining <= 0L) {
                        token?.let(controller::serviceFailed)
                        stopSelf()
                        return@launch
                    }
                    // No progress must not renew a stuck operation's wake lock indefinitely.
                    wakeLock?.acquire(remaining)
                    token?.let { controller.serviceFlags(it, running = true, held = wakeLock?.isHeld == true) }
                } catch (_: RuntimeException) {
                    token?.let(controller::serviceFailed)
                    stopSelf()
                    return@launch
                }
                delay(WAKE_RENEWAL_MILLIS)
            }
        }
    }

    private fun releaseWakeLock() {
        renewal?.cancel()
        renewal = null
        wakeLock?.takeIf { it.isHeld }?.release()
        token?.let { controller.serviceFlags(it, running = true, held = false) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { controller.stop(token) }
    override fun onTimeout(startId: Int, fgsType: Int) {
        token?.let(controller::serviceFailed)
        stopSelf(startId)
    }
    override fun onDestroy() {
        observer?.cancel()
        releaseWakeLock()
        scope.cancel()
        token?.let(controller::serviceDestroyed)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    internal companion object {
        const val ACTION_STOP = "com.masterofchessstrategy.STOP_BACKGROUND_CHESS"
        const val NOTIFICATION_ID = 2401
        const val WAKE_RENEWAL_MILLIS = 30_000L
    }
}
