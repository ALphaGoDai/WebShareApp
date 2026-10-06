package com.webshare.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.WebView
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App 级 TextToSpeech 单例：给注入脚本里的 speechSynthesis 补丁当后端。
 * Android WebView 不实现 Web Speech API，网页（轰轰爱学习等）的朗读功能全走这里：
 * JS 侧 Android.ttsSpeak(...) → 系统 TTS → onDone/onError 回调 window.__webshareTtsDone(id, err)。
 * MainActivity.onCreate 里调 init() 一次。
 */
object TtsEngine {

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready: Boolean = false

    @Volatile
    private var failed: Boolean = false

    /** 引擎初始化完成前到达的朗读请求，就绪后按序补播 */
    private val pending = CopyOnWriteArrayList<() -> Unit>()

    /** 进行中的朗读：utteranceId -> 发起它的页面（onDone/onError/onStop 时回调那个页面） */
    private val live = ConcurrentHashMap<String, WebView>()

    /** 关心语音列表变化（voiceschanged）的页面 */
    @Volatile
    private var voicesListener: WebView? = null

    /** 已经给哪个页面发过 voiceschanged：防止 getVoices → 事件 → getVoices 转圈 */
    @Volatile
    private var voicesNotifiedFor: WebView? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            failed = !ready
            Log.i(TAG, "TTS init status=$status ready=$ready voices=${tts?.voices?.size ?: -1}")
            if (ready) notifyVoicesChanged()
            mainHandler.post {
                val todo = pending.toList()
                pending.clear()
                todo.forEach { it() }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                utteranceId?.let { finish(it, false) }
            }

            /** stop() 停掉在念的那句：按浏览器行为补一个 end，页面的 onend 链才不断 */
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { finish(it, false) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { finish(it, true) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { finish(it, true) }
            }
        })
    }

    /** 标签页关掉时把 WebView 从登记表里摘掉，别留着已 destroy 的引用 */
    fun forget(webView: WebView) {
        if (voicesListener === webView) voicesListener = null
        if (voicesNotifiedFor === webView) voicesNotifiedFor = null
        live.entries.removeAll { it.value === webView }
    }

    private fun finish(id: String, err: Boolean) {
        val wv = live.remove(id) ?: return
        Log.i(TAG, "TTS finish id=$id err=$err")
        mainHandler.post {
            try {
                wv.evaluateJavascript(
                    "window.__webshareTtsDone && window.__webshareTtsDone('$id', ${if (err) "true" else "false"})",
                    null
                )
            } catch (e: Exception) {
            }
        }
    }

    private fun notifyVoicesChanged() {
        val wv = voicesListener ?: return
        voicesNotifiedFor = wv
        mainHandler.post {
            try {
                wv.evaluateJavascript("window.__webshareTtsVoices && window.__webshareTtsVoices()", null)
            } catch (e: Exception) {
            }
        }
    }

    private fun localeFor(lang: String): Locale {
        val l = lang.trim()
        return if (l.startsWith("en", true)) Locale.US else Locale.SIMPLIFIED_CHINESE
    }

    /** 语音列表 JSON（[{name, lang}]）；顺带记住要通知的页面 */
    fun voicesJson(webView: WebView?): String {
        if (webView != null) {
            voicesListener = webView
            // 引擎早就绪、页面才来问（常见于冷启动后才打开的学习站点）：给这一页补一次 voiceschanged
            if (ready && voicesNotifiedFor !== webView) notifyVoicesChanged()
        }
        val engine = tts ?: return "[]"
        if (!ready) return "[]"
        return try {
            val arr = org.json.JSONArray()
            val voices = engine.voices ?: emptySet()
            // 没装数据的在线语音（讯飞/Google 的 network voice）选了也念不出来，排在后面并降权
            val usable = voices
                .filter { v -> v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
                .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name ?: "" }))
            val seen = HashSet<String>()
            for (v in usable) {
                val loc = v.locale ?: continue
                val tag = when (loc.language?.lowercase()) {
                    // 有的引擎把普通话标成 cmn，站点按 lang 含 "zh" 过滤，统一归一成 zh-CN
                    "cmn", "zh" -> "zh-CN"
                    else -> loc.toLanguageTag().ifEmpty { loc.language ?: "" }
                }
                val name = v.name ?: continue
                if (!seen.add("$name|$tag")) continue
                arr.put(org.json.JSONObject().put("name", name).put("lang", tag))
            }
            arr.toString()
        } catch (e: Exception) {
            "[]"
        }
    }

    fun speak(
        webView: WebView,
        text: String,
        lang: String,
        rate: Double,
        pitch: Double,
        voice: String,
        id: String
    ) {
        if (text.isBlank() || id.isBlank()) return
        live[id] = webView
        if (failed) {
            finish(id, true)
            return
        }
        if (!ready) {
            pending.add { runSpeak(text, lang, rate, pitch, voice, id) }
            return
        }
        runSpeak(text, lang, rate, pitch, voice, id)
    }

    private fun runSpeak(text: String, lang: String, rate: Double, pitch: Double, voice: String, id: String) {
        val engine = tts
        if (engine == null || failed) {
            finish(id, true)
            return
        }
        mainHandler.post {
            try {
                val langRes = engine.setLanguage(localeFor(lang))
                if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // 系统 TTS 没有中文数据：让页面收到 error 走它的降级链，而不是静默无声
                    Log.w(TAG, "TTS 语言不支持 lang=$lang res=$langRes")
                    finish(id, true)
                    return@post
                }
                // 语音要放在语言之后设置，否则 setLanguage 会把页面挑的嗓音顶掉
                pickVoice(engine, voice)
                engine.setSpeechRate(rate.coerceIn(0.3, 3.0).toFloat())
                engine.setPitch(pitch.coerceIn(0.3, 2.0).toFloat())
                val res = engine.speak(text, TextToSpeech.QUEUE_ADD, null, id)
                Log.i(TAG, "TTS speak id=$id len=${text.length} langRes=$langRes voice=${voice.ifEmpty { "-" }} res=$res")
                if (res != TextToSpeech.SUCCESS) finish(id, true)
            } catch (e: Exception) {
                Log.w(TAG, "TTS speak 异常 id=$id", e)
                finish(id, true)
            }
        }
    }

    /** 页面选定的语音（名字来自 voicesJson）；找不到就沿用按语言挑的默认嗓音 */
    private fun pickVoice(engine: TextToSpeech, voiceName: String) {
        if (voiceName.isBlank()) return
        try {
            val match = engine.voices?.firstOrNull { it.name == voiceName } ?: return
            engine.voice = match
        } catch (e: Exception) {
        }
    }

    fun stop() {
        live.clear()
        mainHandler.post {
            try {
                tts?.stop()
            } catch (e: Exception) {
            }
        }
    }

    private const val TAG = "WebShareApp"
}
