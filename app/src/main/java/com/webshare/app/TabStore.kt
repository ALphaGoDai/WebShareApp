package com.webshare.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 标签页列表落盘。App 退后台后进程可能被系统回收（或被用户从最近任务划掉），
 * 内存里的 WebView 救不回来，能带走的是每个标签页的网址和标题：
 * 开着标签页时随操作随写（开/关/切换/页面加载完/退到后台），下次启动照这份记录重建。
 */
object TabStore {

    /**
     * @param alias 用户长按标签标题设的备注名（空 = 没设）。它同时是语音快捷指令的名字：
     *              说「打开<App名><备注名>」就切到这个标签页，不用去设置里配。
     */
    class SavedTab(val url: String, val title: String, val alias: String = "")

    class SavedState(val tabs: List<SavedTab>, val current: Int)

    private const val PREFS = "webshare_tabs"
    private const val KEY = "state"

    fun save(context: Context, tabs: List<SavedTab>, current: Int, commit: Boolean = false) {
        val arr = JSONArray()
        for (t in tabs) {
            val o = JSONObject().put("u", t.url).put("t", t.title)
            if (t.alias.isNotEmpty()) o.put("a", t.alias)
            arr.put(o)
        }
        val json = JSONObject().put("current", current).put("tabs", arr).toString()
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json)
        // 退到后台这类"写完就可能被杀"的场合用 commit：apply 的异步落盘会随进程一起消失
        if (commit) edit.commit() else edit.apply()
    }

    fun load(context: Context): SavedState? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("tabs") ?: return null
            val tabs = mutableListOf<SavedTab>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("u")
                if (u.isNotEmpty()) tabs.add(SavedTab(u, o.optString("t"), o.optString("a")))
            }
            if (tabs.isEmpty()) return null
            SavedState(tabs, obj.optInt("current", 0).coerceIn(0, tabs.size - 1))
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
