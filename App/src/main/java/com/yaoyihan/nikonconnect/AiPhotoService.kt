package com.yaoyihan.nikonconnect

import android.app.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class AiPhotoService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId = 0
    private var ready = false
    private val controller get() = AiPhotoController.shared(this)
    override fun onCreate() {
        super.onCreate()
        channels(this)
        try {
            ServiceCompat.startForeground(this, ONGOING_ID, progressNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Link:AI").apply { setReferenceCounted(false) }
            ready = true
        } catch (error: Exception) { controller.stopActive(); stopSelf(); return }
        scope.launch {
            controller.tasks.collect { tasks ->
                if (tasks.none { it.active } && latestStartId != 0) stopSelfResult(latestStartId)
                else postNotification(this@AiPhotoService, ONGOING_ID, progressNotification())
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (!ready) { controller.stopActive(); stopSelf(startId); return START_NOT_STICKY }
        wakeLock?.apply { if (isHeld) release(); acquire(360_000L) }
        controller.startPending()
        if (controller.tasks.value.none { it.active }) stopSelfResult(startId)
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?) = null
    override fun onTimeout(startId: Int, fgsType: Int) { controller.stopActive(); stopSelf() }
    override fun onDestroy() {
        controller.stopActive()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
    private fun progressNotification(): Notification {
        val active = controller.tasks.value.filter { it.active }
        return NotificationCompat.Builder(this, PROGRESS_CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Link · AI 图片处理中 ${active.size}/3")
            .setContentText(active.joinToString(" · ") { it.message })
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    }
    companion object {
        private const val PROGRESS_CHANNEL = "ai_image_progress"
        private const val RESULT_CHANNEL = "ai_image_results"
        private const val ONGOING_ID = 3100
        private fun postNotification(context: Context, id: Int, notification: Notification) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
        }
        private fun channels(context: Context) {
            context.getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, "AI 图片生成进度", NotificationManager.IMPORTANCE_LOW))
                createNotificationChannel(NotificationChannel(RESULT_CHANNEL, "AI 图片生成结果", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
        internal fun notifyResult(context: Context, task: AiPhotoTask, message: String, saved: DownloadRecord?) {
            channels(context)
            val id = task.id.hashCode() and Int.MAX_VALUE
            val intent = if (saved == null) Intent(context, MainActivity::class.java) else
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(saved.uri), "image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(context, RESULT_CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (saved == null) "AI 图片处理失败" else "AI 图片已保存到相册")
                .setContentText("${task.sourceName}：$message").setStyle(NotificationCompat.BigTextStyle().bigText("${task.sourceName}：$message"))
                .setAutoCancel(true).setContentIntent(pending).build()
            postNotification(context, id, notification)
        }
    }
}
