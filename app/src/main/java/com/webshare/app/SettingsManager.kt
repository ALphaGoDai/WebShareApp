package com.webshare.app

import android.content.Context

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateSettings()
    }

    var url: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var dohEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DOH_ENABLED, value).apply()

    var dohUrl: String
        get() = prefs.getString(KEY_DOH_URL, DEFAULT_DOH_URL) ?: DEFAULT_DOH_URL
        set(value) = prefs.edit().putString(KEY_DOH_URL, value).apply()

    private fun migrateSettings() {
        val version = prefs.getInt(KEY_SETTINGS_VERSION, 0)
        if (version < 1) {
            val oldDoh = prefs.getString(KEY_DOH_URL, "") ?: ""
            if (oldDoh.isEmpty() || oldDoh.contains("alidns")) {
                prefs.edit().putString(KEY_DOH_URL, DEFAULT_DOH_URL).apply()
            }
            prefs.edit().putBoolean(KEY_DOH_ENABLED, true).apply()
            prefs.edit().putInt(KEY_SETTINGS_VERSION, 1).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "webshare_settings"
        private const val KEY_URL = "url"
        private const val KEY_DOH_ENABLED = "doh_enabled"
        private const val KEY_DOH_URL = "doh_url"
        private const val KEY_SETTINGS_VERSION = "settings_version"
        const val DEFAULT_DOH_URL = "https://doh.pub/dns-query"
    }
}
