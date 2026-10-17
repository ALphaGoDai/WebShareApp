package com.webshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「缓存视频」的前台服务：一次跑一个（串行），进度发在通知栏（渠道「视频缓存」），
 * 完成后通知能点开「缓存视频」列表离线看。任务从 [start] 进来，可以连点多个排队。
 */
class VideoCacheService : Service() {

    private val queue = ConcurrentLinkedQueue<CacheRequest>()
    private val worker = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private val bannerId = 2

    @Volatile
    private var active: VideoCacher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CACHE -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (url.isNullOrEmpty()) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                val req = CacheRequest(
                    url = url,
                    kind = intent.getStringExtra(EXTRA_KIND) ?: MediaWatch.KIND_FILE,
                    mime = intent.getStringExtra(EXTRA_MIME) ?: "",
                    pageUrl = intent.getStringExtra(EXTRA_REFERER) ?: "",
                    title = intent.getStringExtra(EXTRA_TITLE) ?: "视频"
                )
                queue.add(req)
                pending.incrementAndGet()
                startProgressForeground(req.title, 0, -1, "排队中…")
                worker.execute { drain() }
            }
            ACTION_CANCEL -> {
                active?.cancel()
                Log.i(TAG, "用户取消了缓存")
            }
            else -> stopSelfIfIdle()
        }
        return START_NOT_STICKY
    }

    /** 串行把队列里的活干完 */
    private fun drain() {
        while (true) {
            val req = queue.poll() ?: break
            runOne(req)
            pending.decrementAndGet()
        }
        stopSelfIfIdle()
    }

    private fun runOne(req: CacheRequest) {
        val cacher = VideoCacher(this, req)
        active = cacher
        var lastPercent = -1
        cacher.run(object : VideoCacher.Listener {
            override fun onProgress(bytes: Long, totalBytes: Long, note: String) {
                val pct = if (totalBytes > 0) ((bytes * 100) / totalBytes).toInt() else -1
                if (pct == lastPercent && note.isEmpty()) return
                lastPercent = pct
                notifyProgress(req.title, bytes, totalBytes, note)
            }

            override fun onDone(entry: VideoCacheStore.Entry) {
                Log.i(TAG, "缓存完成：${entry.fileName} ${DownloadService.fmtSize(entry.size)}")
                notifyDone(entry)
            }

            override fun onError(message: String) {
                Log.w(TAG, "缓存失败：$message")
                notifyFailed(req.title, message)
            }
        })
        active = null
    }

    private fun stopSelfIfIdle() {
        if (pending.get() <= 0 && queue.isEmpty()) stopSelfSafely()
    }

    private fun stopSelfSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (e: Exception) {
        }
        stopSelf()
    }

    // ---------- 通知 ----------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "视频缓存", NotificationManager.IMPORTANCE_LOW)
        ch.description = "缓存网页视频的进度"
        nm.createNotificationChannel(ch)
    }

    private fun startProgressForeground(title: String, done: Long, total: Long, note: String) {
        val n = buildProgress(title, done, total, note)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    bannerId, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(bannerId, n)
            }
        } catch (e: Exception) {
            try {
                startForeground(bannerId, n)
            } catch (_: Exception) {
            }
        }
    }

    private fun notifyProgress(title: String, done: Long, total: Long, note: String) {
        try {
            NotificationManagerCompat.from(this).notify(bannerId, buildProgress(title, done, total, note))
        } catch (_: Exception) {
        }
    }

    private fun buildProgress(title: String, done: Long, total: Long, note: String): Notification {
        val text = buildString {
            if (total > 0) append("${DownloadService.fmtSize(done)} / ${DownloadService.fmtSize(total)}")
            else append(DownloadService.fmtSize(done))
            if (note.isNotEmpty()) append(" · ").append(note)
        }
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, VideoCacheService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在缓存：$title")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancel)
        if (total > 0) b.setProgress(100, ((done * 100) / total).toInt().coerceIn(0, 100), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notifyDone(entry: VideoCacheStore.Entry) {
        val open = PendingIntent.getActivity(
            this, (entry.id % 100000).toInt(),
            Intent(this, DownloadsActivity::class.java)
                .putExtra(DownloadsActivity.EXTRA_TAB, DownloadsActivity.TAB_CACHE_VALUE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("已缓存：${entry.title}")
            .setContentText("${DownloadService.fmtSize(entry.size)} · 点这里离线看")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(this).notify((entry.id % 100000).toInt(), n)
        } catch (_: Exception) {
        }
    }

    private fun notifyFailed(title: String, message: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("缓存失败：$title")
            .setContentText(message)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(3, n)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "VideoCache"
        private const val CHANNEL_ID = "webshare_videocache"

        const val ACTION_CACHE = "com.webshare.app.action.CACHE_VIDEO"
        const val ACTION_CANCEL = "com.webshare.app.action.CACHE_CANCEL"
        const val EXTRA_URL = "url"
        const val EXTRA_KIND = "kind"
        const val EXTRA_MIME = "mime"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REFERER = "referer"

        fun start(context: Context, item: MediaWatch.Item, title: String) {
            val intent = Intent(context, VideoCacheService::class.java).apply {
                action = ACTION_CACHE
                putExtra(EXTRA_URL, item.url)
                putExtra(EXTRA_KIND, item.kind)
                putExtra(EXTRA_MIME, item.mime)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_REFERER, item.pageUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
