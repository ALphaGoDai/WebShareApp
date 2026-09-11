package com.webshare.app

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DohDnsResolver(private val dohUrl: String) : Dns {

    private val dohClient: OkHttpClient

    init {
        val dohHost = try {
            java.net.URL(dohUrl).host
        } catch (e: Exception) {
            ""
        }

        val bootstrapDns = BootstrapDns(dohHost)
        dohClient = OkHttpClient.Builder()
            .dns(bootstrapDns)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(val addresses: List<InetAddress>, val expiry: Long)

    override fun lookup(hostname: String): List<InetAddress> {
        val cached = cache[hostname]
        if (cached != null && System.currentTimeMillis() < cached.expiry) {
            return cached.addresses
        }

        try {
            val query = buildDnsQuery(hostname)
            val request = Request.Builder()
                .url(dohUrl)
                .post(query.toRequestBody("application/dns-message".toMediaType()))
                .header("Accept", "application/dns-message")
                .build()

            dohClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UnknownHostException("DoH request failed: ${response.code}")
                }
                val body = response.body?.bytes()
                    ?: throw UnknownHostException("DoH response empty")

                val ips = parseDnsResponse(body)
                if (ips.isEmpty()) {
                    throw UnknownHostException("No A record for $hostname")
                }

                val addresses = ips.map { InetAddress.getByName(it) }
                cache[hostname] = CacheEntry(addresses, System.currentTimeMillis() + CACHE_TTL)
                return addresses
            }
        } catch (e: Exception) {
            cache[hostname]?.let { return it.addresses }
            try {
                return Dns.SYSTEM.lookup(hostname)
            } catch (e2: Exception) {
            }
            val ex = UnknownHostException(hostname)
            ex.initCause(e)
            throw ex
        }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeShort(Random.nextInt(65536))
        dos.writeShort(0x0100)
        dos.writeShort(1)
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)

        val cleanDomain = domain.trimEnd('.')
        for (part in cleanDomain.split(".")) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0)

        dos.writeShort(1)
        dos.writeShort(1)

        return baos.toByteArray()
    }

    private fun parseDnsResponse(data: ByteArray): List<String> {
        val dis = DataInputStream(ByteArrayInputStream(data))
        val ips = mutableListOf<String>()

        dis.readShort()
        dis.readShort()
        val qdcount = dis.readShort().toInt() and 0xFFFF
        val ancount = dis.readShort().toInt() and 0xFFFF
        dis.readShort()
        dis.readShort()

        for (i in 0 until qdcount) {
            skipName(dis)
            dis.readShort()
            dis.readShort()
        }

        for (i in 0 until ancount) {
            skipName(dis)
            val type = dis.readShort().toInt() and 0xFFFF
            dis.readShort()
            dis.readInt()
            val rdlength = dis.readShort().toInt() and 0xFFFF

            if (type == 1 && rdlength == 4) {
                val b1 = dis.readByte().toInt() and 0xFF
                val b2 = dis.readByte().toInt() and 0xFF
                val b3 = dis.readByte().toInt() and 0xFF
                val b4 = dis.readByte().toInt() and 0xFF
                ips.add("$b1.$b2.$b3.$b4")
            } else {
                var remaining = rdlength
                while (remaining > 0) {
                    val skipped = dis.skip(remaining.toLong()).toInt()
                    if (skipped <= 0) break
                    remaining -= skipped
                }
            }
        }

        return ips
    }

    private fun skipName(dis: DataInputStream) {
        while (true) {
            val len = dis.readByte().toInt() and 0xFF
            if (len == 0) return
            if (len and 0xC0 == 0xC0) {
                dis.readByte()
                return
            }
            dis.skipBytes(len)
        }
    }

    companion object {
        private const val CACHE_TTL = 5 * 60 * 1000L

        private val DOH_BOOTSTRAP_IPS = mapOf(
            "dns.alidns.com" to listOf("223.5.5.5", "223.6.6.6"),
            "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
            "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
            "doh.pub" to listOf("1.12.12.12", "120.53.53.53"),
            "doh.360.cn" to listOf("101.226.4.6", "218.30.118.6"),
            "223.5.5.5" to listOf("223.5.5.5"),
            "223.6.6.6" to listOf("223.6.6.6"),
            "1.1.1.1" to listOf("1.1.1.1"),
            "8.8.8.8" to listOf("8.8.8.8"),
        )
    }

    private class BootstrapDns(private val dohHost: String) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname == dohHost && dohHost.isNotEmpty()) {
                DOH_BOOTSTRAP_IPS[hostname]?.let { ips ->
                    return ips.map { InetAddress.getByName(it) }
                }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }
}
