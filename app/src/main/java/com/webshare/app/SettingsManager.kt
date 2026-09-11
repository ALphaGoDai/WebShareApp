package com.webshare.app

import android.content.Context

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var dohEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DOH_ENABLED, value).apply()

    var dohUrl: String
        get() = prefs.getString(KEY_DOH_URL, DEFAULT_DOH_URL) ?: DEFAULT_DOH_URL
        set(value) = prefs.edit().putString(KEY_DOH_URL, value).apply()

    companion object {
        private const val PREFS_NAME = "webshare_settings"
        private const val KEY_URL = "url"
        private const val KEY_DOH_ENABLED = "doh_enabled"
        private const val KEY_DOH_URL = "doh_url"
        const val DEFAULT_DOH_URL = "https://dns.alidns.com/dns-query"
    }
}
