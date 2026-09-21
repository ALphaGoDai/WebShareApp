package com.webshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载前台服务：走 App 自己的 DNS(DoH) + TLS 通道把文件流式写入用户选定目录。
 *
 * 之所以不走系统的 DownloadManager：那套网络栈用系统 DNS，在劫持环境下必然失败，
 * 也带不上网页的登录 Cookie。
 */
class DownloadService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val active = AtomicInteger(0)
    private val notificationSeq = AtomicInteger(1000)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (intent?.action != ACTION_DOWNLOAD || url.isNullOrEmpty()) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val name = intent.getStringExtra(EXTRA_NAME) ?: "download.bin"
        val mime = intent.getStringExtra(EXTRA_MIME) ?: "application/octet-stream"
        val treeUri = intent.getStringExtra(EXTRA_TREE)
        val referer = intent.getStringExtra(EXTRA_REFERER)

        startProgressForeground(name, 0, -1)
        active.incrementAndGet()

        executor.execute {
            var ok = false
            var error: String? = null
            var savedUri: Uri? = null
            var repairNote = ""
            try {
                savedUri = download(url, name, mime, treeUri, referer) { done, total ->
                    notifyProgress(name, done, total)
                }
                repairNote = tryRepairDownloaded(url, mime, savedUri)
                ok = true
            } catch (e: Exception) {
                error = e.message ?: "未知错误"
            }

            val remaining = active.decrementAndGet()
            if (ok && savedUri != null) {
                notifyDone(name, savedUri, mime, repairNote)
            } else {
                notifyFailed(name, error ?: "下载失败")
            }
            if (remaining <= 0) stopSelfSafely()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    // ---------- 实际下载 ----------

    private fun download(
        url: String,
        name: String,
        mime: String,
        treeUri: String?,
        referer: String?,
        onProgress: (Long, Long) -> Unit
    ): Uri {
        val target = createTarget(name, mime, treeUri)

        val settings = SettingsManager(this)
        val resolver = if (settings.dohEnabled && settings.dohUrl.isNotEmpty()) {
            try {
                DohDnsResolver(settings.dohUrl)
            } catch (e: Exception) {
                null
            }
        } else null

        val configuredHost = try {
            Uri.parse(settings.url.trim()).host ?: ""
        } catch (e: Exception) {
            ""
        }

        val headers = mutableMapOf<String, String>()
        if (!referer.isNullOrEmpty()) headers["Referer"] = referer
        headers["Accept"] = "*/*"

        val engine = HttpEngine(resolver, configuredHost)

        var streamed = false
        try {
            engine.open(url, headers).use { resp ->
                if (resp.status !in 200..299) {
                    throw IOException("服务器返回 HTTP ${resp.status}")
                }
                val total = resp.contentLength
                resp.input.use { input ->
                    streamTo(input, target.output, total, name, onProgress)
                }
            }
            streamed = true
        } finally {
            try { target.output.close() } catch (_: Exception) {}
            if (!streamed) {
                // 失败/中断：删掉半截文件，避免留下坏文件
                try { target.delete() } catch (_: Exception) {}
            }
        }

        target.finish()
        return target.uri
    }

    /**
     * 下载完成后，若是录像类文件且时间轴有病（坏帧），就地替换成修复后的内容——
     * 与浏览器里播放的、以及保存对话框显示的大小保持一致，落盘的文件在任何播放器里都能放。
     * 修复不了（正常文件/非视频/超大文件）时原样保留。
     */
    private fun tryRepairDownloaded(url: String, mime: String, uri: Uri): String {
        val looksVideo = mime.startsWith("video/") || mime.isEmpty() ||
            mime.contains("octet-stream")
        if (!looksVideo) return ""
        val resolver = contentResolver
        return try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
            if (bytes.size < 4096 || bytes.size > MediaRepair.MAX_REPAIR_BYTES) return ""
            val fixed = MediaRepair.repairBytes(bytes, url, cacheDir) ?: return ""
            // 字节已在内存里，落到目标（wt = 截断后写入）
            resolver.openOutputStream(uri, "wt")?.use {
                it.write(fixed.bytes)
                it.flush()
            } ?: return ""
            Log.i(TAG, "downloaded file repaired: ${bytes.size} -> ${fixed.bytes.size} bytes")
            fixed.note
        } catch (e: Exception) {
            Log.w(TAG, "repair downloaded file failed: ${e.message}")
            ""
        }
    }

    private fun streamTo(
        input: InputStream,
        output: OutputStream,
        total: Long,
        name: String,
        onProgress: (Long, Long) -> Unit
    ) {        val buf = ByteArray(64 * 1024)
        var done = 0L
        var lastNotify = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            done += n
            val now = System.currentTimeMillis()
            if (now - lastNotify > 400) {
                lastNotify = now
                onProgress(done, total)
            }
        }
        output.flush()
        onProgress(done, total)
    }

    /** 目标文件：MediaStore 系统下载目录，或用户选的 SAF 目录 */
    private class Target(
        val uri: Uri,
        val output: OutputStream,
        private val onDelete: () -> Unit
    ) {
        fun finish() { try { output.close() } catch (_: Exception) {} }
        fun delete() { try { output.close() } catch (_: Exception) {}; onDelete() }
    }

    private fun createTarget(name: String, mime: String, treeUri: String?): Target {
        val resolver = contentResolver
        val safeName = sanitize(name)

        if (treeUri.isNullOrEmpty()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw IOException("系统版本过低，请在确认框里选择保存目录")
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS
                )
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在系统下载目录创建文件")
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("无法写入系统下载目录")
            return Target(uri, out) { try { resolver.delete(uri, null, null) } catch (_: Exception) {} }
        }

        val tree = Uri.parse(treeUri)
        val parentDoc = try {
            DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
        } catch (e: Exception) {
            throw IOException("保存目录已失效，请重新选择")
        }
        val unique = uniqueName(parentDoc, safeName)
        val docUri = try {
            DocumentsContract.createDocument(resolver, parentDoc, mime, unique)
        } catch (e: Exception) {
            null
        } ?: throw IOException("无法在所选目录创建文件（目录可能已被删除或权限失效）")

        val out = resolver.openOutputStream(docUri)
            ?: throw IOException("无法写入所选目录")
        return Target(docUri, out) {
            try { DocumentsContract.deleteDocument(resolver, docUri) } catch (_: Exception) {}
        }
    }

    /** SAF 提供方对重名文件不一定会自动改名，这里先查一遍占用情况 */
    private fun uniqueName(parentDoc: Uri, name: String): String {
        val existing = mutableSetOf<String>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                parentDoc, DocumentsContract.getDocumentId(parentDoc)
            )
            contentResolver.query(
                children,
                arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.let { existing.add(it.lowercase(Locale.ROOT)) }
                }
            }
        } catch (_: Exception) {
        }
        if (existing.isEmpty() || !existing.contains(name.lowercase(Locale.ROOT))) return name

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 1000) {
            val candidate = "$base ($i)$ext"
            if (!existing.contains(candidate.lowercase(Locale.ROOT))) return candidate
            i++
        }
        return "$base-${System.currentTimeMillis()}$ext"
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[/\\\\:*?\"<>|\\r\\n\\t]"), "_").trim()
        return cleaned.ifEmpty { "download.bin" }.take(180)
    }

    // ---------- 通知 ----------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, "文件下载", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "网页资源保存到手机时的进度与结果通知"
        }
        nm.createNotificationChannel(channel)
    }

    private fun startProgressForeground(name: String, done: Long, total: Long) {
        val notification = buildProgressNotification(name, done, total)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(FOREGROUND_ID, notification)
            } catch (_: Exception) {}
        }
    }

    private fun notifyProgress(name: String, done: Long, total: Long) {
        val nm = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !nm.areNotificationsEnabled()
        ) return
        try {
            nm.notify(FOREGROUND_ID, buildProgressNotification(name, done, total))
        } catch (_: Exception) {}
    }

    private fun buildProgressNotification(name: String, done: Long, total: Long): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载")
            .setContentText(progressText(name, done, total))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (total > 0) {
            builder.setProgress(100, ((done * 100) / total).toInt().coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun progressText(name: String, done: Long, total: Long): String {
        val size = if (total > 0) {
            "${fmtSize(done)} / ${fmtSize(total)} (${(done * 100 / total)}%)"
        } else {
            fmtSize(done)
        }
        return "$name · $size"
    }

    private fun notifyDone(name: String, uri: Uri, mime: String, repairNote: String = "") {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this, notificationSeq.get(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText(if (repairNote.isEmpty()) name else "$name · $repairNote")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_view, "打开", pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(notificationSeq.incrementAndGet(), notification)
        } catch (_: Exception) {}
        toastOnMain(if (repairNote.isEmpty()) "已保存：$name" else "已保存：$name（$repairNote）")
    }

    private fun notifyFailed(name: String, error: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("下载失败")
            .setContentText("$name · $error")
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$name\n$error"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(notificationSeq.incrementAndGet(), notification)
        } catch (_: Exception) {}
        toastOnMain("下载失败：$error")
    }

    private fun toastOnMain(text: String) {
        android.os.Handler(mainLooper).post {
            try {
                Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val CHANNEL_ID = "webshare_downloads"
        private const val FOREGROUND_ID = 1
        const val ACTION_DOWNLOAD = "com.webshare.app.action.DOWNLOAD"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_MIME = "mime"
        const val EXTRA_TREE = "tree"
        const val EXTRA_REFERER = "referer"

        fun start(
            context: Context,
            url: String,
            name: String,
            mime: String,
            treeUri: String?,
            referer: String?
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_MIME, mime)
                if (treeUri != null) putExtra(EXTRA_TREE, treeUri)
                if (referer != null) putExtra(EXTRA_REFERER, referer)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun fmtSize(bytes: Long): String {
            if (bytes < 0) return "未知大小"
            if (bytes < 1024) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
            return String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }

        private const val TAG = "WebShareApp"
    }
}
