package com.webshare.app

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 语音快捷指令：用户配的「名称 → 网址」。
 *
 * - 每条都注册成桌面长按图标可见的快捷方式（动态快捷方式），名字就是用户会说的那个词（如「乘车码」）；
 * - 点快捷方式、或外部工具用 `webshare://voice/<id>` 唤起，都直达配置的页面；
 * - 手机助手（小艺 / 快捷指令）只会「打开应用」时，靠 referrer 认出"这次是助手唤起的"，
 *   就打开设置里指定的那条（[assistantTarget]）——网页始终在我们自己的浏览器里（自定义 DNS 生效）。
 */
object VoiceShortcuts {

    private const val TAG = "WebShareApp"
    private const val PREFS = "webshare_voice"
    private const val KEY_LIST = "shortcuts"
    private const val KEY_TARGET = "assistant_target"
    private const val KEY_LAST_REFERRER = "last_referrer"

    /** 深链 scheme：webshare://voice/<id>（快捷方式用；也给自动化工具留个入口） */
    const val SCHEME = "webshare"
    private const val SHORTCUT_ID_PREFIX = "voice_"

    /** 语音助手/桌面快捷指令这类"外部替我打开 App"的包名线索（华为：vassistant / 快捷指令） */
    private val ASSISTANT_HINTS = listOf(
        "vassistant", "voiceassist", "voice", "assistant", "celia",
        "hivoice", "hiassistant", "quickaction", "shortcut", "quick", "smart", "hiboard"
    )

    /** 这些来源是"用户自己点开的"（桌面、最近任务、系统界面、设置），不能当成助手 */
    private val USER_LAUNCH_HINTS = listOf(
        "launcher", "home", "desktop", "systemui", "recents", "settings",
        "shell", "packageinstaller", "permissioncontroller", "android"
    )

    class Item(val id: String, val name: String, val url: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): MutableList<Item> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return mutableListOf()
        val out = mutableListOf<Item>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val name = o.optString("name")
                val url = o.optString("url")
                if (id.isNotEmpty() && name.isNotEmpty() && url.isNotEmpty()) {
                    out.add(Item(id, name, url))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "语音快捷指令解析失败: ${e.message}")
        }
        return out
    }

    private fun persist(context: Context, items: List<Item>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url))
        }
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
        sync(context)
    }

    /** 加一条；名称/网址重复的直接返回已有那条（避免点两次加出两条一样的） */
    fun add(context: Context, name: String, url: String): Item {
        val items = load(context)
        items.firstOrNull { it.name == name && it.url == url }?.let { return it }
        val item = Item(newId(), name, url)
        items.add(item)
        persist(context, items)
        return item
    }

    fun remove(context: Context, id: String) {
        val items = load(context).filter { it.id != id }.toMutableList()
        persist(context, items)
        if (assistantTarget(context) == id) setAssistantTarget(context, "")
    }

    fun find(context: Context, id: String): Item? = load(context).firstOrNull { it.id == id }

    /** 助手唤起 App 时直达的那条（空 = 不特别处理，正常开标签页） */
    fun assistantTarget(context: Context): String =
        prefs(context).getString(KEY_TARGET, "") ?: ""

    fun setAssistantTarget(context: Context, id: String) {
        prefs(context).edit().putString(KEY_TARGET, id).apply()
    }

    /** 最近一次"是谁把 App 叫起来的"（设置页显示，排查语音助手不生效时用） */
    fun lastReferrer(context: Context): String =
        prefs(context).getString(KEY_LAST_REFERRER, "") ?: ""

    fun setLastReferrer(context: Context, pkg: String) {
        prefs(context).edit().putString(KEY_LAST_REFERRER, pkg).apply()
    }

    private fun newId(): String {
        val chars = "0123456789abcdef"
        return (1..8).map { chars.random() }.joinToString("")
    }

    /**
     * 把配置同步成动态快捷方式。桌面长按图标能看到；手机助手支持"按快捷方式名直达"的话，
     * 说「打开<App名><名称>」就能进来。
     */
    fun sync(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return   // ShortcutManager 从 7.1 起
        val sm = context.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager ?: return
        val items = load(context)
        val limit = sm.maxShortcutCountPerActivity.coerceAtLeast(1)
        val use = items.take(limit)
        if (items.size > use.size) {
            Log.w(TAG, "快捷方式最多 ${limit} 条，只注册前 ${use.size} 条（再多的只在列表里）")
        }
        val infos = use.mapIndexed { index, item ->
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$SCHEME://voice/${item.id}"), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ShortcutInfo.Builder(context, SHORTCUT_ID_PREFIX + item.id)
                .setShortLabel(item.name)
                .setLongLabel("${item.name} · ${hostOf(item.url)}")
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .setRank(index)
                .build()
        }
        try {
            sm.dynamicShortcuts = infos
            Log.i(TAG, "语音快捷方式已同步：${use.joinToString("、") { it.name }}")
        } catch (e: Exception) {
            Log.w(TAG, "注册快捷方式失败: ${e.message}")
        }
    }

    private fun hostOf(url: String): String = try {
        Uri.parse(url).host ?: url
    } catch (e: Exception) {
        url
    }

    /**
     * 这次唤起是不是语音助手/桌面快捷指令这类"别人替我打开"的。
     *
     * 认得出的助手包名（vassistant、快捷指令…）直接算；此外——只有当调用方**明显不是**用户自己
     * 点开的来源（桌面/最近任务/系统界面/adb shell）时，也当作"外部程序替我打开的"：手机助手
     * 的包名各机型不一样，认不出就漏掉的话用户就白配了。桌面点开图标永远不会误判。
     */
    fun looksLikeAssistant(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (ASSISTANT_HINTS.any { p.contains(it) }) return true
        return p.isNotEmpty() && USER_LAUNCH_HINTS.none { p.contains(it) }
    }
}
