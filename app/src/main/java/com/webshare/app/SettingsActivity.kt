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

        // 加载当前设置
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

            if (dohEnabled && dohUrl.isNotEmpty() && !dohUrl.startsWith("https://")) {
                Snackbar.make(btnSave, "DNS 服务器地址需要以 https:// 开头", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            settingsManager.url = url
            settingsManager.dohEnabled = dohEnabled
            settingsManager.dohUrl = dohUrl

            setResult(RESULT_OK)
            finish()
        }

        // 快速填充常用 DoH 服务器
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
    }

    private fun updateDohUrlVisibility() {
        layoutDohUrl.visibility = if (switchDoh.isChecked) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
