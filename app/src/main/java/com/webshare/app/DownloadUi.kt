package com.webshare.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/** 从 Content-Disposition / URL 里推断保存用的文件名 */
object DownloadNaming {

    fun fileName(url: String, contentDisposition: String?, mime: String?): String {
        val cd = contentDisposition ?: ""

        // RFC 5987: filename*=UTF-8''%E6%96%87%E5%AD%97.txt
        rfc5987(cd)?.let { return sanitize(it) }

        // 普通 filename=xxx（可能带引号）
        val plain = Regex("filename\\s*=\\s*(\"[^\"]*\"|[^;\\r\\n]+)", RegexOption.IGNORE_CASE)
            .find(cd)?.groupValues?.get(1)?.trim()?.trim('"')
        if (!plain.isNullOrBlank()) return sanitize(decode(plain))

        // 退到 URL 末段
        val fromUrl = urlFileName(url)
        if (fromUrl != null) return sanitize(fromUrl)

        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return if (ext != null) "download.$ext" else "download.bin"
    }

    private fun rfc5987(cd: String): String? {
        val m = Regex("filename\\*\\s*=\\s*([^;\\r\\n]+)", RegexOption.IGNORE_CASE).find(cd)
            ?: return null
        var value = m.groupValues[1].trim()
        val quoteIdx = value.indexOf("''")
        if (quoteIdx >= 0) value = value.substring(quoteIdx + 2)
        return decode(value)
    }

    private fun urlFileName(url: String): String? {
        return try {
            val path = Uri.parse(url).lastPathSegment ?: return null
            val name = java.net.URLDecoder.decode(path, "UTF-8")
            name.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(raw: String): String = try {
        java.net.URLDecoder.decode(raw, "UTF-8")
    } catch (e: Exception) {
        raw
    }

    private fun sanitize(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .replace(Regex("[/\\\\:*?\"<>|\\r\\n\\t]"), "_")
            .trim()
        return cleaned.ifBlank { "download.bin" }.take(180)
    }

    /**
     * 打开文件用的 MIME。网页给的下载类型经常是 application/octet-stream（GitHub 的
     * release 资产就是这样），照它去 ACTION_VIEW 找不到任何应用——尤其是 .apk：
     * 电视/手机的安装器只认 application/vnd.android.package-archive，必须按扩展名补上。
     */
    fun resolveMime(name: String?, mime: String?): String {
        val given = mime?.trim().orEmpty()
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext == "apk") return APK_MIME
        val generic = given.isEmpty() || given == "*/*" ||
            given.equals("application/octet-stream", ignoreCase = true)
        if (generic) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return given.ifEmpty { "application/octet-stream" }
    }

    const val APK_MIME = "application/vnd.android.package-archive"
}

/** 下载前的「保存到哪个目录」确认框 */
object DownloadUi {

    fun showSaveDialog(
        activity: Activity,
        store: SaveLocationStore,
        fileName: String,
        sizeText: String?,
        onStart: (SaveLocation) -> Unit,
        onChooseOther: () -> Unit
    ) {
        val locs = store.locations()
        if (locs.isEmpty()) {
            // 没有可用目录（老系统上没记住任何 SAF 目录）：直接进系统目录选择器
            onChooseOther()
            return
        }

        val labels = locs.map { it.label }.toMutableList()
        val otherIndex = labels.size
        labels.add("＋ 选择其他目录…")

        var selected = locs.indexOfLast { it.id == store.lastUsedId }
        if (selected < 0) selected = 0

        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val padSmall = (8 * density).toInt()

        // 注意：AppCompat 的 AlertDialog 同时设 message + setSingleChoiceItems 时
        // 列表根本不会渲染(contentPanel 只显示 message)，所以这里用自定义视图。
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, padSmall, pad, 0)
        }

        container.addView(TextView(activity).apply {
            text = buildString {
                append(fileName)
                if (!sizeText.isNullOrEmpty()) append("　(").append(sizeText).append(")")
            }
            textSize = 14f
            setPadding(0, 0, 0, (12 * density).toInt())
        })

        val group = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
        }
        labels.forEachIndexed { index, label ->
            val btn = RadioButton(activity).apply {
                id = 1000 + index
                text = label
                textSize = 15f
                isChecked = index == selected
            }
            group.addView(btn)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            selected = checkedId - 1000
        }
        container.addView(group)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("保存到哪个目录？")
            .setView(container)
            .setPositiveButton("开始下载", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                if (selected == otherIndex) onChooseOther() else onStart(locs[selected])
            }
        }
        dialog.show()
    }

    /** 打开已下载的文件（下载完成的浮按钮和「已下载」列表共用） */
    fun openFile(activity: Activity, uriStr: String, name: String?, mime: String?) {
        val resolved = DownloadNaming.resolveMime(name, mime)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriStr), resolved)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                activity,
                if (resolved == DownloadNaming.APK_MIME) "没有找到安装程序，无法安装这个文件"
                else "没有应用能打开这种文件",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/** 下载完成后浮在底栏上方的「立即打开」按钮，3 秒后自动消失 */
object DownloadOpenFab {

    private var popup: PopupWindow? = null
    private var anchor: View? = null
    private var restoreFocus: View? = null
    private val hide = Runnable { dismiss() }

    fun show(activity: Activity, uriStr: String, name: String?, mime: String?) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss()
        val tv = TvMode.isTv(activity)
        val density = activity.resources.displayMetrics.density
        val label = TextView(activity).apply {
            text = "立即打开"
            setTextColor(Color.WHITE)
            textSize = if (tv) 16f else 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((20 * density).toInt(), (11 * density).toInt(),
                (20 * density).toInt(), (11 * density).toInt())
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(activity, R.color.purple_500))
                cornerRadius = 24 * density
            }
            isClickable = true
            if (tv) {
                // 电视上让遥控器能选中它：3 秒内按 OK 直接打开
                isFocusable = true
                isFocusableInTouchMode = true
                foreground = ContextCompat.getDrawable(activity, R.drawable.bg_tv_focus)
            }
            setOnClickListener {
                dismiss()
                DownloadUi.openFile(activity, uriStr, name, mime)
            }
        }
        val p = PopupWindow(
            label,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isOutsideTouchable = false
            isFocusable = tv
            elevation = 16 * density
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        popup = p
        val root = activity.window?.decorView ?: run {
            dismiss()
            return
        }
        anchor = root
        if (tv) restoreFocus = activity.currentFocus
        val y = ((if (tv) 40 else 56) + 14) * density
        try {
            p.showAtLocation(root, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, y.toInt())
        } catch (e: Exception) {
            Log.w("WebShareApp", "download open fab failed: ${e.message}")
            dismiss()
            return
        }
        if (tv) label.post { label.requestFocus() }
        root.postDelayed(hide, 3000)
    }

    fun dismiss() {
        anchor?.removeCallbacks(hide)
        anchor = null
        runCatching { popup?.dismiss() }
        popup = null
        restoreFocus?.let { prev ->
            restoreFocus = null
            if (prev.isAttachedToWindow) prev.post { prev.requestFocus() }
        }
    }
}
