package com.masterofchessstrategy.games.competition_mahjong.auto.production

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.R
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoPlaybackState
import com.masterofchessstrategy.games.competition_mahjong.auto.isAutoTerminal
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class AutoBackgroundPosition(val series: Int, val hand: Int, val completed: Int, val events: Int)

internal data class AutoBackgroundLease(
    val token: String,
    val owner: String,
    val runId: String,
    val position: AutoBackgroundPosition,
    val delayMillis: Long,
    val returnTo: ComponentName?,
    val pause: (String) -> Unit,
    val foreground: Boolean = true,
    val ready: Boolean = false,
)

/** The foreground service shares the table's existing playback owner. */
internal object CompetitionMahjongAutoBackground {
    private val mutableLease = MutableStateFlow<AutoBackgroundLease?>(null)
    val lease = mutableLease.asStateFlow()

    fun eligible(state: CompetitionMahjongAutoPlaybackState): Boolean = with(state.activity) {
        runId != null && frame != null && settings.backgroundEnabled && !settings.paused &&
            !needsSave && !finished && !closed && !state.halted
    }

    fun start(context: Context, owner: String, state: CompetitionMahjongAutoPlaybackState, returnTo: ComponentName?,
        pause: (String) -> Unit): Boolean {
        if (mutableLease.value != null || !eligible(state) || !notificationsAllowed(context)) return false
        val next = AutoBackgroundLease(UUID.randomUUID().toString(), owner, checkNotNull(state.activity.runId),
            position(state), state.activity.settings.backgroundDelayMillis, returnTo, pause)
        if (!mutableLease.compareAndSet(null, next)) return false
        return try {
            ContextCompat.startForegroundService(context, Intent(context, CompetitionMahjongAutoBackgroundService::class.java)
                .putExtra(EXTRA_TOKEN, next.token))
            true
        } catch (_: RuntimeException) {
            release(next.token)
            false
        }
    }

    fun update(owner: String, state: CompetitionMahjongAutoPlaybackState, foreground: Boolean) {
        mutableLease.update { current ->
            if (current == null || current.owner != owner) current
            else if (current.runId == state.activity.runId && eligible(state)) {
                current.copy(position = position(state), foreground = foreground,
                    delayMillis = state.activity.settings.delayMillis(state.activity.frame?.isAutoTerminal() == true,
                        foreground = false))
            } else null
        }
    }

    fun stopOwner(owner: String) { mutableLease.update { it?.takeUnless { active -> active.owner == owner } } }

    fun acknowledge(token: String) {
        mutableLease.update { if (it?.token == token) it.copy(ready = true) else it }
    }

    fun release(token: String) {
        mutableLease.update { it?.takeUnless { active -> active.token == token } }
    }

    fun requestStop(token: String) {
        val current = mutableLease.value?.takeIf { it.token == token } ?: return
        if (mutableLease.compareAndSet(current, null)) current.pause(current.runId)
    }

    fun notificationsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT < 26) return manager.areNotificationsEnabled()
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "国标麻将后台演局", NotificationManager.IMPORTANCE_LOW))
        }
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun position(state: CompetitionMahjongAutoPlaybackState): AutoBackgroundPosition = with(state.activity) {
        AutoBackgroundPosition(seriesIndex, handIndex, completedHands, eventsAfterOpening)
    }

    const val CHANNEL = "competition_mahjong_auto_runtime"
    const val EXTRA_TOKEN = "competition_mahjong_auto_runtime_token"
}

/** Keeps the user-started table alive and holds CPU only while its owner is out of view. */
class CompetitionMahjongAutoBackgroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var token: String? = null
    private var observer: Job? = null
    private var watchdog: Job? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "MasterofChessStrategy:CompetitionMahjongAuto").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra(CompetitionMahjongAutoBackground.EXTRA_TOKEN)
        if (intent?.action == ACTION_STOP) {
            requested?.let(CompetitionMahjongAutoBackground::requestStop)
            if (token == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (requested == null) {
            if (token == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val current = CompetitionMahjongAutoBackground.lease.value
        try {
            // A revoked pending launch still has to fulfil Android's startForeground contract.
            val notification = notification(current?.token ?: requested, current?.returnTo)
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (_: RuntimeException) {
            CompetitionMahjongAutoBackground.release(requested)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (current?.token != requested) {
            if (current == null || token == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        token = requested
        CompetitionMahjongAutoBackground.acknowledge(requested)
        observer?.cancel()
        observer = scope.launch {
            CompetitionMahjongAutoBackground.lease.collect { lease ->
                if (lease?.token != requested) {
                    releaseWakeLock()
                    if (CompetitionMahjongAutoBackground.lease.value == null) stopSelf()
                } else if (lease.foreground) releaseWakeLock() else watchProgress(requested)
            }
        }
        return START_NOT_STICKY
    }

    private fun watchProgress(expected: String) {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            var position: AutoBackgroundPosition? = null
            var progressAt = SystemClock.elapsedRealtime()
            while (true) {
                val current = CompetitionMahjongAutoBackground.lease.value
                    ?.takeIf { it.token == expected && !it.foreground } ?: break
                val now = SystemClock.elapsedRealtime()
                if (position != current.position) {
                    position = current.position
                    progressAt = now
                }
                val remaining = current.delayMillis + 30_000 - (now - progressAt)
                if (remaining <= 0 || !CompetitionMahjongAutoBackground.notificationsAllowed(this@CompetitionMahjongAutoBackgroundService)) {
                    CompetitionMahjongAutoBackground.release(expected)
                    break
                }
                try {
                    wakeLock.acquire(remaining)
                } catch (_: RuntimeException) {
                    CompetitionMahjongAutoBackground.release(expected)
                    break
                }
                delay(minOf(remaining, 5_000))
            }
        }
    }

    private fun releaseWakeLock() {
        watchdog?.cancel()
        watchdog = null
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun notification(token: String, returnTo: ComponentName?): android.app.Notification {
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java)
            .setComponent(returnTo ?: ComponentName(this, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, NOTIFICATION_ID, Intent(this, javaClass).setAction(ACTION_STOP)
            .setData("mocs-mahjong://stop/$token".toUri()).putExtra(CompetitionMahjongAutoBackground.EXTRA_TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CompetitionMahjongAutoBackground.CHANNEL)
            .setSmallIcon(R.drawable.ic_chess_notification)
            .setContentTitle("国标麻将后台演局")
            .setContentText("返回牌桌查看进度，或停止后台演局。")
            .setContentIntent(open).setSilent(true).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).addAction(0, "停止后台演局", stop).build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { token?.let(CompetitionMahjongAutoBackground::release) }
    override fun onTimeout(startId: Int, fgsType: Int) {
        token?.let(CompetitionMahjongAutoBackground::release)
        stopSelf(startId)
    }
    override fun onDestroy() {
        observer?.cancel()
        releaseWakeLock()
        scope.cancel()
        token?.let(CompetitionMahjongAutoBackground::release)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    internal companion object {
        const val NOTIFICATION_ID = 2402
        private const val ACTION_STOP = "com.masterofchessstrategy.STOP_COMPETITION_MAHJONG_BACKGROUND"
    }
}
