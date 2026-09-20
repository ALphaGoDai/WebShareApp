package com.webshare.app

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
    private lateinit var etUrl: TextInputEditText
    private lateinit var switchDoh: Switch
    private lateinit var etDohUrl: TextInputEditText
    private lateinit var layoutDohUrl: View
    private lateinit var etUpdateUrl: TextInputEditText
    private lateinit var btnUpdate: Button
    private lateinit var btnDiagnose: Button
    private lateinit var btnSave: Button
    private lateinit var tvVersion: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        settingsManager = SettingsManager(this)

        etUrl = findViewById(R.id.etUrl)
        switchDoh = findViewById(R.id.switchDoh)
        etDohUrl = findViewById(R.id.etDohUrl)
        layoutDohUrl = findViewById(R.id.layoutDohUrl)
        etUpdateUrl = findViewById(R.id.etUpdateUrl)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnDiagnose = findViewById(R.id.btnDiagnose)
        btnSave = findViewById(R.id.btnSave)
        tvVersion = findViewById(R.id.tvVersion)

        etUrl.setText(settingsManager.url)
        switchDoh.isChecked = settingsManager.dohEnabled
        etDohUrl.setText(settingsManager.dohUrl)
        etUpdateUrl.setText(settingsManager.updateUrl)
        updateDohUrlVisibility()

        tvVersion.text = "当前版本: " + try {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "-" }

        switchDoh.setOnCheckedChangeListener { _, isChecked ->
            updateDohUrlVisibility()
        }

        btnUpdate.setOnClickListener {
            val u = etUpdateUrl.text?.toString()?.trim() ?: ""
            if (u.isEmpty()) {
                Snackbar.make(btnUpdate, "更新地址为空，请先保存设置", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            try {
                val parsed = Uri.parse(u)
                if (parsed.scheme != "http" && parsed.scheme != "https") {
                    Snackbar.make(btnUpdate, "更新地址需要以 http:// 或 https:// 开头", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val intent = Intent(Intent.ACTION_VIEW, parsed)
                startActivity(intent)
            } catch (e: Exception) {
                Snackbar.make(btnUpdate, "无法打开: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }

        btnDiagnose.setOnClickListener {
            runDiagnostics()
        }

        btnSave.setOnClickListener {
            var url = etUrl.text?.toString()?.trim() ?: ""
            val dohEnabled = switchDoh.isChecked
            val dohUrl = etDohUrl.text?.toString()?.trim() ?: ""
            val updateUrl = etUpdateUrl.text?.toString()?.trim() ?: ""

            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
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
            settingsManager.dohUrl = if (dohUrl.isEmpty()) SettingsManager.DEFAULT_DOH_URL else dohUrl
            settingsManager.updateUrl = if (updateUrl.isEmpty()) SettingsManager.DEFAULT_UPDATE_URL else updateUrl

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

    private fun runDiagnostics() {
        val targetUrl = etUrl.text?.toString()?.trim() ?: ""
        if (targetUrl.isEmpty()) {
            Snackbar.make(btnDiagnose, "请先填写网页地址", Snackbar.LENGTH_LONG).show()
            return
        }

        btnDiagnose.isEnabled = false
        btnDiagnose.text = "诊断中..."

        Thread {
            val report = try {
                Diagnostics.run(
                    targetUrl,
                    switchDoh.isChecked,
                    etDohUrl.text?.toString()?.trim() ?: "",
                    packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                )
            } catch (e: Exception) {
                "诊断异常: ${e.message}"
            }
            runOnUiThread {
                btnDiagnose.isEnabled = true
                btnDiagnose.text = "网络诊断"
                showReport(report)
            }
        }.start()
    }

    private fun showReport(report: String) {
        val tv = TextView(this).apply {
            text = report
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(48, 36, 48, 36)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("网络诊断报告")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .show()
        tv.gravity = Gravity.START
    }

    private fun updateDohUrlVisibility() {
        layoutDohUrl.visibility = if (switchDoh.isChecked) View.VISIBLE else View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
