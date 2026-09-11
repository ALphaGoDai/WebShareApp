package com.webshare.app

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONObject

class WebAppInterface(private val context: Context) {

    @Volatile
    private var sharedText: String = ""

    @Volatile
    private var sharedType: String = "none"

    fun setSharedContent(text: String, type: String) {
        sharedText = text
        sharedType = type
    }

    fun clearSharedContent() {
        sharedText = ""
        sharedType = "none"
    }

    @JavascriptInterface
    fun getSharedText(): String = sharedText

    @JavascriptInterface
    fun getSharedType(): String = sharedType

    @JavascriptInterface
    fun getSettings(): String {
        val settings = SettingsManager(context)
        return JSONObject().apply {
            put("url", settings.url)
            put("dohEnabled", settings.dohEnabled)
            put("dohUrl", settings.dohUrl)
        }.toString()
    }
}
