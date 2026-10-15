package com.webshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「到某地自动打开某一页」用的定位小工具。
 *
 * 只在打开 App 的那一刻读一次位置（用户在走路，位置很新），做完判定就完事：
 * 不申请后台定位、不持续监听、不上报任何地方。拿不到位置（没权限/没开定位服务/室内没信号）
 * 就当作"不在范围内"，照常打开起始页。
 */
object GeoRule {

    private const val TAG = "GeoRule"

    data class Rule(val lat: Double, val lng: Double, val radiusMeters: Int, val url: String)

    /** 两个坐标之间的距离（米，haversine；地球按平均半径算，误差 <0.5%，判定范围足够用） */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_008.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /** 距离的中文说法：80 米 / 1.2 公里 */
    fun describe(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} 米"
        else String.format("%.1f 公里", meters / 1000)

    /** 纬度/经度写成固定 6 位小数（约 0.1 米精度，够公交站这种场景） */
    fun formatCoord(v: Double): String = String.format("%.6f", v)

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun manager(ctx: Context): LocationManager? =
        ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** 有权限时按"定位精度"排的候选 provider */
    private fun providerChain(ctx: Context): List<String> {
        val lm = manager(ctx) ?: return emptyList()
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val out = mutableListOf<String>()
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            if (p == LocationManager.GPS_PROVIDER && !fine) continue
            try {
                if (lm.allProviders.contains(p)) out.add(p)
            } catch (e: Exception) {
            }
        }
        return out
    }

    /** 系统里到底开没开定位（关了就别等了，直接按"不在范围内"处理） */
    @SuppressLint("MissingPermission")
    fun locationServiceUsable(ctx: Context): Boolean {
        val lm = manager(ctx) ?: return false
        if (providerChain(ctx).isEmpty()) return false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) return true
            } catch (e: Exception) {
            }
        }
        return false
    }

    /** 缓存里最后那个位置（各 provider 里挑最新的）；比 maxAgeMs 旧就当没有 */
    @SuppressLint("MissingPermission")
    fun bestLastKnown(ctx: Context, maxAgeMs: Long = 10 * 60 * 1000): Location? {
        if (!hasPermission(ctx)) return null
        val lm = manager(ctx) ?: return null
        val fresh = mutableListOf<Location>()
        for (p in providerChain(ctx)) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (e: Exception) {
                null
            } ?: continue
            if (System.currentTimeMillis() - l.time > maxAgeMs) {
                Log.i(TAG, "最后已知位置太旧（${(System.currentTimeMillis() - l.time) / 1000} 秒前，来自 ${l.provider}），不采用")
                continue
            }
            fresh.add(l)
        }
        if (fresh.isEmpty()) return null
        // 同一个点上，基站的粗定位能有 1~2 公里误差，GPS 只有几十米：
        // 有"够准的"就只在够准的里面挑最新的，免得被一条新鲜但很粗的基站定位抢走
        val precise = fresh.filter { accurateEnough(it, 0) }
        return (if (precise.isNotEmpty()) precise else fresh).maxByOrNull { it.time }
    }

    /** 这次定位够不够准：精度比判定范围还差就判不了（宁可不动，也别在 1 公里外把人送错页） */
    fun accurateEnough(loc: Location, radiusMeters: Int): Boolean =
        !loc.hasAccuracy() || loc.accuracy <= maxOf(radiusMeters.toFloat(), 200f)

    /** 精度的人话：±30 米 */
    fun describeAccuracy(loc: Location): String =
        if (loc.hasAccuracy()) "±${loc.accuracy.toInt()} 米" else "精度未知"

    /**
     * 要一次位置：拿到就回调，超时也用"能拿到的最后位置"兜底（可能很旧），再不行回调 null。
     * 同时向各 provider 要，但**只有够准的（≤100 米）才立刻收工**——不然一条又快又粗的
     * 基站定位会把真的 GPS 结果顶掉（模拟器上实测过：网络定位把 GPS 差出一千多公里）。
     * 不够准时留着最准的那条，等窗口结束再给。回调在主线程，用完立刻摘监听。
     */
    @SuppressLint("MissingPermission")
    fun requestSingle(ctx: Context, timeoutMs: Long, onResult: (Location?) -> Unit) {
        if (!hasPermission(ctx)) {
            onResult(null)
            return
        }
        val lm = manager(ctx) ?: run { onResult(null); return }
        val providers = providerChain(ctx)
        if (providers.isEmpty()) {
            onResult(null)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        var done = false
        var best: Location? = null
        lateinit var listener: LocationListener

        fun finish(loc: Location?) {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            try {
                lm.removeUpdates(listener)
            } catch (e: Exception) {
            }
            onResult(loc)
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (accurateEnough(location, 0) && location.accuracy <= GOOD_ACCURACY_M) {
                    finish(location)
                    return
                }
                val cur = best
                if (cur == null || location.accuracy < cur.accuracy) best = location
            }
        }
        handler.postDelayed({
            // 窗口结束：先用手上最准的那条，没有再退回最后已知位置（哪怕旧）
            finish(best ?: bestLastKnown(ctx, Long.MAX_VALUE))
        }, timeoutMs)
        try {
            for (p in providers) {
                @Suppress("DEPRECATION")
                lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
            }
        } catch (e: Exception) {
            Log.w(TAG, "请求定位失败: ${e.message}")
            finish(null)
        }
    }

    /** 小于这个精度（米）就认为"够用了，立刻收工" */
    private const val GOOD_ACCURACY_M = 100f
}
