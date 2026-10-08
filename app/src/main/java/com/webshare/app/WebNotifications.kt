package com.webshare.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * 系统通知。
 *
 * 网页自己发的提醒（`new Notification(...)`）在 Android WebView 里是**完全没有实现**的：
 * 对象存在、但 `requestPermission` 永远拿不到权限、`new Notification` 什么都不弹，所以
 * 「页面提醒你一下」这件事在 App 里等于静默失败。这里把注入脚本（assets/shim.js）转过来的
 * 网页通知发成真正的系统通知，点一下回到本应用。
 *
 * 下载进度/完成走的是 DownloadService 自己那条通道（"文件下载"），两条通道分开，
 * 用户可以在系统通知设置里单独关掉其中一条。
 */
object WebNotifications {

    private const val TAG = "WebShareApp"
    private const val CHANNEL_ID = "webshare_pages"

    /** 通知带这个额外字段时，点开不要走"语音助手唤起"的判定（见 MainActivity.handleIntent） */
    const val EXTRA_FROM_NOTIFICATION = "webshare_from_notification"

    private val seq = AtomicInteger(2000)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "网页通知", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "网页自己发出的通知（WebView 不支持网页通知，由本应用代发）"
        nm.createNotificationChannel(ch)
    }

    /** Android 13+ 要运行时授权；更早版本只有用户没在系统里整体关掉通知就能发 */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** 发一条网页通知；标题为空时用来源站点兜底 */
    fun post(context: Context, title: String, body: String, source: String) {
        if (!canPost(context)) {
            Log.i(TAG, "网页通知没有通知权限，丢弃：$title / $body")
            return
        }
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_FROM_NOTIFICATION, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title.ifBlank { source.ifBlank { "网页通知" } })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
        if (source.isNotBlank()) builder.setSubText(source)
        try {
            NotificationManagerCompat.from(context).notify(seq.incrementAndGet(), builder.build())
            Log.i(TAG, "网页通知已发：$title / $body（来源 $source）")
        } catch (e: Exception) {
            Log.w(TAG, "发网页通知失败: ${e.message}")
        }
    }

    /** 跳到系统的「本应用通知设置」——收不到通知时让用户有个地方可查 */
    fun openSystemSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "打不开系统通知设置: ${e.message}")
        }
    }
}
