package com.webshare.app

import android.content.Context

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateSettings()
    }

    /**
     * 默认打开的网页地址。设置页已不再提供这一项（地址都从「新建标签页」的地址栏输），
     * 这个值只在冷启动没有可恢复的标签页时作为起始页/新标签页预填用。
     */
    var url: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var dohEnabled: Boolean
        get() = prefs.getBoolean(KEY_DOH_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DOH_ENABLED, value).apply()

    var dohUrl: String
        get() = prefs.getString(KEY_DOH_URL, DEFAULT_DOH_URL) ?: DEFAULT_DOH_URL
        set(value) = prefs.edit().putString(KEY_DOH_URL, value).apply()

    var updateUrl: String
        get() = prefs.getString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL
        set(value) = prefs.edit().putString(KEY_UPDATE_URL, value).apply()

    private fun migrateSettings() {
        val version = prefs.getInt(KEY_SETTINGS_VERSION, 0)
        if (version < 2) {
            val oldDoh = prefs.getString(KEY_DOH_URL, "") ?: ""
            // Migrate from any previous version: ensure DNSPod default
            if (oldDoh.isEmpty() || oldDoh.contains("alidns")) {
                prefs.edit().putString(KEY_DOH_URL, DEFAULT_DOH_URL).apply()
            }
            prefs.edit().putBoolean(KEY_DOH_ENABLED, true).apply()
            prefs.edit().putInt(KEY_SETTINGS_VERSION, 2).apply()
        }
        if (version < 3) {
            // 安装包名 v1.0.44 起从 WebShareApp.apk 改成 DnsWeb.apk，老地址会 404；
            // 用户自己填过别的地址就不动
            val oldUpdate = prefs.getString(KEY_UPDATE_URL, "") ?: ""
            if (oldUpdate.isEmpty() || oldUpdate.endsWith("/download/WebShareApp.apk")) {
                prefs.edit().putString(KEY_UPDATE_URL, DEFAULT_UPDATE_URL).apply()
            }
            prefs.edit().putInt(KEY_SETTINGS_VERSION, 3).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "webshare_settings"
        private const val KEY_URL = "url"
        private const val KEY_DOH_ENABLED = "doh_enabled"
        private const val KEY_DOH_URL = "doh_url"
        private const val KEY_SETTINGS_VERSION = "settings_version"
        private const val KEY_UPDATE_URL = "update_url"
        const val DEFAULT_URL = "http://send.nbhonghong.top:7777/"
        const val DEFAULT_DOH_URL = "https://doh.pub/dns-query"
        const val DEFAULT_UPDATE_URL =
            "https://github.com/ALphaGoDai/WebShareApp/releases/latest/download/DnsWeb.apk"
    }
}
