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

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

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

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UnknownHostException("DoH 请求失败: ${response.code}")
                }
                val body = response.body?.bytes()
                    ?: throw UnknownHostException("DoH 响应为空")

                val ips = parseDnsResponse(body)
                if (ips.isEmpty()) {
                    throw UnknownHostException("未找到 $hostname 的 A 记录")
                }

                val addresses = ips.map { InetAddress.getByName(it) }
                cache[hostname] = CacheEntry(addresses, System.currentTimeMillis() + CACHE_TTL)
                return addresses
            }
        } catch (e: Exception) {
            cache[hostname]?.let { return it.addresses }
            val ex = UnknownHostException(hostname)
            ex.initCause(e)
            throw ex
        }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        // Header
        dos.writeShort(Random.nextInt(65536))    // ID
        dos.writeShort(0x0100)                     // Flags: standard query, recursion desired
        dos.writeShort(1)                          // QDCOUNT
        dos.writeShort(0)                          // ANCOUNT
        dos.writeShort(0)                          // NSCOUNT
        dos.writeShort(0)                          // ARCOUNT

        // Question - QNAME
        val cleanDomain = domain.trimEnd('.')
        for (part in cleanDomain.split(".")) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0) // End of QNAME

        dos.writeShort(1) // QTYPE: A
        dos.writeShort(1) // QCLASS: IN

        return baos.toByteArray()
    }

    private fun parseDnsResponse(data: ByteArray): List<String> {
        val dis = DataInputStream(ByteArrayInputStream(data))
        val ips = mutableListOf<String>()

        dis.readShort() // ID
        dis.readShort() // Flags
        val qdcount = dis.readShort().toInt() and 0xFFFF
        val ancount = dis.readShort().toInt() and 0xFFFF
        dis.readShort() // NSCOUNT
        dis.readShort() // ARCOUNT

        // Skip questions
        for (i in 0 until qdcount) {
            skipName(dis)
            dis.readShort() // QTYPE
            dis.readShort() // QCLASS
        }

        // Parse answers
        for (i in 0 until ancount) {
            skipName(dis)
            val type = dis.readShort().toInt() and 0xFFFF
            dis.readShort() // CLASS
            dis.readInt()   // TTL
            val rdlength = dis.readShort().toInt() and 0xFFFF

            if (type == 1 && rdlength == 4) {
                // A record
                val b1 = dis.readByte().toInt() and 0xFF
                val b2 = dis.readByte().toInt() and 0xFF
                val b3 = dis.readByte().toInt() and 0xFF
                val b4 = dis.readByte().toInt() and 0xFF
                ips.add("$b1.$b2.$b3.$b4")
            } else {
                // Skip RDATA
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
                dis.readByte() // Compression pointer
                return
            }
            dis.skipBytes(len)
        }
    }

    companion object {
        private const val CACHE_TTL = 5 * 60 * 1000L // 5 minutes
    }
}
