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

    // ---------- 到某地自动打开（「走到吕厝公交站就开乘车码」） ----------

    var geoEnabled: Boolean
        get() = prefs.getBoolean(KEY_GEO_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_GEO_ENABLED, value).apply()

    /** 目标纬度/经度：存字符串（十进制度），避免 Double 进 prefs 丢精度 */
    var geoLat: String
        get() = prefs.getString(KEY_GEO_LAT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEO_LAT, value).apply()

    var geoLng: String
        get() = prefs.getString(KEY_GEO_LNG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEO_LNG, value).apply()

    /** 判定范围（米） */
    var geoRadius: Int
        get() = prefs.getInt(KEY_GEO_RADIUS, DEFAULT_GEO_RADIUS)
        set(value) = prefs.edit().putInt(KEY_GEO_RADIUS, value).apply()

    var geoUrl: String
        get() = prefs.getString(KEY_GEO_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEO_URL, value).apply()

    /** 解析成一条可用规则；没开、缺坐标、缺网址都返回 null（= 不生效） */
    fun geoRule(): GeoRule.Rule? {
        if (!geoEnabled) return null
        val lat = geoLat.trim().toDoubleOrNull() ?: return null
        val lng = geoLng.trim().toDoubleOrNull() ?: return null
        val url = geoUrl.trim()
        if (url.isEmpty()) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        if (lat == 0.0 && lng == 0.0) return null                 // 没填过/填错的情况
        return GeoRule.Rule(lat, lng, geoRadius.coerceIn(30, 100_000), url)
    }

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
        private const val KEY_GEO_ENABLED = "geo_enabled"
        private const val KEY_GEO_LAT = "geo_lat"
        private const val KEY_GEO_LNG = "geo_lng"
        private const val KEY_GEO_RADIUS = "geo_radius"
        private const val KEY_GEO_URL = "geo_url"
        const val DEFAULT_GEO_RADIUS = 300
        const val DEFAULT_URL = "http://send.nbhonghong.top:7777/"
        const val DEFAULT_DOH_URL = "https://doh.pub/dns-query"
        const val DEFAULT_UPDATE_URL =
            "https://github.com/ALphaGoDai/WebShareApp/releases/latest/download/DnsWeb.apk"
    }
}
