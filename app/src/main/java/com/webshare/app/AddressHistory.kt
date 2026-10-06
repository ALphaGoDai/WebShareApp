package com.webshare.app

import android.content.Context
import org.json.JSONArray

/**
 * 新建标签页地址栏下方展示的「最近访问」：记录每个真正加载完成的页面地址，
 * 最新的排最前；展示取前 5 条，多存一些（20 条）让删掉一条后还能顶上来。
 */
object AddressHistory {

    private const val PREFS = "webshare_addr_history"
    private const val KEY = "urls"
    private const val MAX_KEEP = 20

    fun record(context: Context, rawUrl: String) {
        val url = normalize(rawUrl) ?: return
        val list = load(context).toMutableList()
        list.removeAll { it == url }
        list.add(0, url)
        while (list.size > MAX_KEEP) list.removeAt(list.size - 1)
        save(context, list)
    }

    fun recent(context: Context, limit: Int = 5): List<String> = load(context).take(limit)

    fun remove(context: Context, url: String) {
        val list = load(context).toMutableList()
        list.removeAll { it == url }
        save(context, list)
    }

    /** 只收真实网页：http(s) 才记；分享文件的虚拟地址、无地址的内置页不进历史 */
    private fun normalize(rawUrl: String): String? {
        if (!rawUrl.startsWith("http://", true) && !rawUrl.startsWith("https://", true)) return null
        if (rawUrl.contains(DohWebViewClient.SHARED_PATH_PREFIX)) return null
        return rawUrl.substringBefore('#')
    }

    private fun load(context: Context): List<String> = try {
        val arr = JSONArray(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        )
        (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(context: Context, list: List<String>) {
        val arr = JSONArray()
        for (u in list) arr.put(u)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
