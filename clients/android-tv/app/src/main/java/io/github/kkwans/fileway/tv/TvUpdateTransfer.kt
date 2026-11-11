package io.github.kkwans.fileway.tv

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import io.github.kkwans.fileway.design.R as Art

internal object TvUpdateTransfer {
    const val ID = 7318100
    const val TOKEN = "tv-update-transfer-token"
    private const val CHANNEL = "fileway-tv-update"

    fun start(context: Context, token: String, size: Long) {
        if (Build.VERSION.SDK_INT >= 34) {
            val network = NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            val job = JobInfo.Builder(ID, ComponentName(context, TvUpdateJobService::class.java))
                .setUserInitiated(true).setRequiredNetwork(network).setEstimatedNetworkBytes(size, 0)
                .setExtras(PersistableBundle().apply { putString(TOKEN, token) }).build()
            check(context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS)
        } else {
            context.startForegroundService(Intent(context, TvUpdateForegroundService::class.java).putExtra(TOKEN, token))
        }
    }

    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT >= 34) context.getSystemService(JobScheduler::class.java).cancel(ID)
    }

    fun updateNotification(context: Context, state: TvUpdateState) {
        if (!state.transferring) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.areNotificationsEnabled()) manager.notify(ID, notification(context, state))
    }

    fun notification(context: Context, state: TvUpdateState): Notification {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "栖卷 TV 更新", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, ID,
            Intent(context, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val total = state.total
        val text = when (state.phase) {
            TvUpdatePhase.DOWNLOADING -> "正在下载到电视 · ${tvSize(state.received)}"
            TvUpdatePhase.VERIFYING -> "正在校验安装包"
            else -> "服务器正在准备更新 · ${tvSize(state.received)}"
        }
        return Notification.Builder(context, CHANNEL).setSmallIcon(Art.drawable.fileway_ic_update)
            .setContentTitle("栖卷 TV 更新").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, if (total != null) (state.received * 100 / total).toInt() else 0,
                total == null || state.phase == TvUpdatePhase.VERIFYING)
            .build()
    }
}

class TvUpdateForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var updates: TvUpdateRuntime
    private var activeToken: String? = null
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        updates = TvUpdateRuntime.get(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(TvUpdateTransfer.ID, TvUpdateTransfer.notification(this, updates.state.value))
        val token = intent?.getStringExtra(TvUpdateTransfer.TOKEN)
        if (token == null) {
            if (activeToken == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (token == activeToken) return START_NOT_STICKY
        val started = updates.startTransfer(token) {
            if (activeToken == token) {
                activeToken = null
                observer?.cancel()
                if (stopSelfResult(startId)) stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        if (!started) {
            if (activeToken == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        activeToken = token
        observer?.cancel()
        observer = scope.launch {
            updates.state.collect { state ->
                TvUpdateTransfer.updateNotification(this@TvUpdateForegroundService, state)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeToken?.let(updates::platformStopped)
        scope.cancel()
        super.onDestroy()
    }
}

class TvUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var updates: TvUpdateRuntime
    private var active: JobParameters? = null
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        updates = TvUpdateRuntime.get(this)
    }

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val token = params.extras.getString(TvUpdateTransfer.TOKEN) ?: return false
        setNotification(params, TvUpdateTransfer.ID, TvUpdateTransfer.notification(this, updates.state.value),
            JOB_END_NOTIFICATION_POLICY_REMOVE)
        active = params
        val started = updates.startTransfer(token) {
            if (active === params) {
                active = null
                observer?.cancel()
                jobFinished(params, false)
            }
        }
        if (!started) {
            active = null
            return false
        }
        observer?.cancel()
        observer = scope.launch {
            updates.state.collect { state ->
                TvUpdateTransfer.updateNotification(this@TvUpdateJobService, state)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (active === params) {
            active = null
            observer?.cancel()
            params.extras.getString(TvUpdateTransfer.TOKEN)?.let(updates::platformStopped)
        }
        return false
    }

    override fun onDestroy() {
        active?.extras?.getString(TvUpdateTransfer.TOKEN)?.let(updates::platformStopped)
        scope.cancel()
        super.onDestroy()
    }
}
