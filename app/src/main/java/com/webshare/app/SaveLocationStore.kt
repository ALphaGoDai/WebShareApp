package com.webshare.app

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个可选的下载保存位置。
 * [treeUri] 为 null 表示系统下载目录（MediaStore，API 29+）。
 */
data class SaveLocation(
    val id: String,
    val label: String,
    val treeUri: Uri?
) {
    val isMediaStore: Boolean get() = treeUri == null
}

/**
 * 记住用户选过哪些目录用于保存下载文件（SAF 授权持久化后跨重启有效）。
 */
class SaveLocationStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var lastUsedId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    /** 可选项：系统下载目录（若支持）+ 已记住的目录 */
    fun locations(): List<SaveLocation> {
        val out = mutableListOf<SaveLocation>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            out.add(SaveLocation(MEDIA_ID, "系统下载目录 Download/", null))
        }
        val arr = dirsArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val uri = obj.optString("uri")
            if (uri.isEmpty()) continue
            out.add(SaveLocation(uri, obj.optString("label", labelOf(uri)), Uri.parse(uri)))
        }
        return out
    }

    fun find(id: String?): SaveLocation? = locations().firstOrNull { it.id == id }

    /** 用户新选定一个目录：记住它（含显示名）并置为默认 */
    fun remember(treeUri: Uri, displayLabel: String? = null): SaveLocation {
        val uriStr = treeUri.toString()
        val label = displayLabel?.takeIf { it.isNotBlank() } ?: labelOf(uriStr)
        val arr = dirsArray()
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("uri") == uriStr) continue
            next.put(obj)
        }
        next.put(JSONObject().put("uri", uriStr).put("label", label))
        prefs.edit().putString(KEY_DIRS, next.toString()).apply()
        return SaveLocation(uriStr, label, treeUri)
    }

    fun forget(id: String) {
        val arr = dirsArray()
        val next = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("uri") == id) continue
            next.put(obj)
        }
        prefs.edit().putString(KEY_DIRS, next.toString()).apply()
    }

    private fun dirsArray(): JSONArray = try {
        JSONArray(prefs.getString(KEY_DIRS, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    /** "content://.../tree/primary%3ADownload%2Fxxx" -> "Download/xxx" */
    private fun labelOf(uriStr: String): String {
        return try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(uriStr))
            docId.substringAfter(':', docId).ifEmpty { docId }
        } catch (e: Exception) {
            "已选目录"
        }
    }

    companion object {
        const val MEDIA_ID = "media"
        private const val PREFS = "webshare_downloads"
        private const val KEY_DIRS = "dirs"
        private const val KEY_LAST = "last_dir"
    }
}
