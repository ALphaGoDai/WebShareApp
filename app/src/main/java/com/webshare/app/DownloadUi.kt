package com.webshare.app

import android.app.Activity
import android.net.Uri
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

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
}
