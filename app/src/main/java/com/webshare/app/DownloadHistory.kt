package com.webshare.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 下载记录（参照浏览器下载页的模型）：一条记录从「正在下载」走到「已完成/失败」，
 * 已下载页面据此展示进度、打开文件、重试或删除。
 *
 * 存在 SharedPreferences 里（JSON 数组，最多保留 [MAX] 条，新的在前）。
 * status: running / done / failed
 */
data class DownloadRecord(
    val id: Long,
    val url: String,
    val name: String,          // 预期文件名（实际落盘名可能因重名调整，见 savedName）
    val mime: String,
    val referer: String?,      // 重试时要带上
    val treeUri: String?,      // null = 系统下载目录（MediaStore）
    val location: String,      // 展示用：保存位置
    val startedAt: Long,
    var received: Long = 0L,
    var total: Long = -1L,
    var status: String = STATUS_RUNNING,
    var savedUri: String? = null,
    var savedName: String? = null,
    var size: Long = -1L,
    var error: String? = null,
    var repairNote: String? = null
) {
    val displaySize: Long get() = if (size >= 0) size else total
    val displayName: String get() = savedName?.takeIf { it.isNotBlank() } ?: name

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"

        fun locationLabel(treeUri: String?): String {
            if (treeUri.isNullOrEmpty()) return "系统下载目录"
            return try {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
                docId.substringAfter(':', docId).ifEmpty { docId }
            } catch (e: Exception) {
                "已选目录"
            }
        }

        fun fromJson(o: JSONObject): DownloadRecord = DownloadRecord(
            id = o.optLong("id"),
            url = o.optString("url"),
            name = o.optString("name"),
            mime = o.optString("mime"),
            referer = o.optString("referer").takeIf { it.isNotEmpty() },
            treeUri = o.optString("tree").takeIf { it.isNotEmpty() },
            location = o.optString("location"),
            startedAt = o.optLong("startedAt"),
            received = o.optLong("received"),
            total = o.optLong("total", -1L),
            status = o.optString("status", STATUS_RUNNING),
            savedUri = o.optString("savedUri").takeIf { it.isNotEmpty() },
            savedName = o.optString("savedName").takeIf { it.isNotEmpty() },
            size = o.optLong("size", -1L),
            error = o.optString("error").takeIf { it.isNotEmpty() },
            repairNote = o.optString("repairNote").takeIf { it.isNotEmpty() }
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("url", url).put("name", name).put("mime", mime)
        .put("referer", referer ?: "").put("tree", treeUri ?: "")
        .put("location", location).put("startedAt", startedAt)
        .put("received", received).put("total", total)
        .put("status", status).put("savedUri", savedUri ?: "").put("savedName", savedName ?: "")
        .put("size", size).put("error", error ?: "").put("repairNote", repairNote ?: "")
}

class DownloadHistory(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 新记录放最前；超出上限丢最旧的 */
    @Synchronized
    fun add(rec: DownloadRecord) {
        val list = load().toMutableList()
        list.removeAll { it.id == rec.id }
        list.add(0, rec)
        save(list.take(MAX))
    }

    @Synchronized
    fun update(id: Long, mutate: (DownloadRecord) -> Unit) {
        val list = load()
        val rec = list.firstOrNull { it.id == id } ?: return
        mutate(rec)
        save(list)
    }

    @Synchronized
    fun remove(id: Long) {
        save(load().filter { it.id != id })
    }

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    /** 新的在前 */
    @Synchronized
    fun all(): List<DownloadRecord> = load()

    /** 服务被杀后 running 记录会悬空：补标成失败 */
    @Synchronized
    fun failStale(error: String) {
        val list = load()
        var dirty = false
        for (rec in list) {
            if (rec.status == DownloadRecord.STATUS_RUNNING) {
                rec.status = DownloadRecord.STATUS_FAILED
                rec.error = error
                dirty = true
            }
        }
        if (dirty) save(list)
    }

    private fun load(): List<DownloadRecord> {
        val arr = try {
            JSONArray(prefs.getString(KEY_ITEMS, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val out = mutableListOf<DownloadRecord>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            try {
                out.add(DownloadRecord.fromJson(o))
            } catch (e: Exception) {
            }
        }
        return out
    }

    private fun save(list: List<DownloadRecord>) {
        val arr = JSONArray()
        for (rec in list) arr.put(rec.toJson())
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "webshare_download_history"
        private const val KEY_ITEMS = "items"
        private const val MAX = 200
    }
}
