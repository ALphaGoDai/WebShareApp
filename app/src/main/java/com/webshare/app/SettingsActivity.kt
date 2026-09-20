package com.webshare.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var etUrl: TextInputEditText
    private lateinit var switchDoh: Switch
    private lateinit var etDohUrl: TextInputEditText
    private lateinit var layoutDohUrl: View
    private lateinit var btnSave: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        settingsManager = SettingsManager(this)

        etUrl = findViewById(R.id.etUrl)
        switchDoh = findViewById(R.id.switchDoh)
        etDohUrl = findViewById(R.id.etDohUrl)
        layoutDohUrl = findViewById(R.id.layoutDohUrl)
        btnSave = findViewById(R.id.btnSave)

        etUrl.setText(settingsManager.url)
        switchDoh.isChecked = settingsManager.dohEnabled
        etDohUrl.setText(settingsManager.dohUrl)
        updateDohUrlVisibility()

        switchDoh.setOnCheckedChangeListener { _, isChecked ->
            updateDohUrlVisibility()
        }

        btnSave.setOnClickListener {
            val url = etUrl.text?.toString()?.trim() ?: ""
            val dohEnabled = switchDoh.isChecked
            val dohUrl = etDohUrl.text?.toString()?.trim() ?: ""

            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                Snackbar.make(btnSave, "网址需要以 http:// 或 https:// 开头", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            if (dohEnabled && dohUrl.isNotEmpty()) {
                val isDoh = dohUrl.startsWith("https://")
                val isIp = DohDnsResolver.isIpAddress(dohUrl)
                if (!isDoh && !isIp) {
                    Snackbar.make(btnSave, "DNS 服务器需要是 https:// 开头的 DoH 地址或纯 IP 地址", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            settingsManager.url = url
            settingsManager.dohEnabled = dohEnabled
            settingsManager.dohUrl = dohUrl

            setResult(RESULT_OK)
            finish()
        }

        // DoH servers
        findViewById<TextView>(R.id.tvGoogleDns)?.setOnClickListener {
            etDohUrl.setText("https://dns.google/dns-query")
        }
        findViewById<TextView>(R.id.tvCloudflareDns)?.setOnClickListener {
            etDohUrl.setText("https://cloudflare-dns.com/dns-query")
        }
        findViewById<TextView>(R.id.tvAliDns)?.setOnClickListener {
            etDohUrl.setText("https://dns.alidns.com/dns-query")
        }
        findViewById<TextView>(R.id.tvDnsPod)?.setOnClickListener {
            etDohUrl.setText("https://doh.pub/dns-query")
        }

        // UDP DNS servers
        findViewById<TextView>(R.id.tvDnsPodUdp)?.setOnClickListener {
            etDohUrl.setText("119.29.29.29")
        }
        findViewById<TextView>(R.id.tvAliUdp)?.setOnClickListener {
            etDohUrl.setText("223.5.5.5")
        }
        findViewById<TextView>(R.id.tvHuaweiDns)?.setOnClickListener {
            etDohUrl.setText("117.50.11.11")
        }
        findViewById<TextView>(R.id.tvGoogleUdp)?.setOnClickListener {
            etDohUrl.setText("8.8.8.8")
        }
    }

    private fun updateDohUrlVisibility() {
        layoutDohUrl.visibility = if (switchDoh.isChecked) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
