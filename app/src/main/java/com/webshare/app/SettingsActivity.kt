package com.webshare.app

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    private lateinit var settingsManager: SettingsManager
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

        switchDoh = findViewById(R.id.switchDoh)
        etDohUrl = findViewById(R.id.etDohUrl)
        layoutDohUrl = findViewById(R.id.layoutDohUrl)
        etUpdateUrl = findViewById(R.id.etUpdateUrl)
        btnUpdate = findViewById(R.id.btnUpdate)
        btnDiagnose = findViewById(R.id.btnDiagnose)
        btnSave = findViewById(R.id.btnSave)
        tvVersion = findViewById(R.id.tvVersion)

        setupVoiceShortcuts()

        findViewById<android.widget.ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        switchDoh.isChecked = settingsManager.dohEnabled
        etDohUrl.setText(settingsManager.dohUrl)
        etUpdateUrl.setText(settingsManager.updateUrl)
        updateDohUrlVisibility()

        tvVersion.text = "v" + try {
            packageManager.getPackageInfo(packageName, 0).versionName
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
            val dohEnabled = switchDoh.isChecked
            val dohUrl = etDohUrl.text?.toString()?.trim() ?: ""
            val updateUrl = etUpdateUrl.text?.toString()?.trim() ?: ""

            if (dohEnabled && dohUrl.isNotEmpty()) {
                val isDoh = dohUrl.startsWith("https://")
                val isIp = DohDnsResolver.isIpAddress(dohUrl)
                if (!isDoh && !isIp) {
                    Snackbar.make(btnSave, "DNS 服务器需要是 https:// 开头的 DoH 地址或纯 IP 地址", Snackbar.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

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

    // ---------- 语音快捷指令 + 网站登录状态 ----------

    private fun setupVoiceShortcuts() {
        findViewById<Button>(R.id.btnVoiceAdd).setOnClickListener { addVoiceShortcut() }
        findViewById<Button>(R.id.btnVoiceTarget).setOnClickListener { chooseVoiceTarget() }
        findViewById<Button>(R.id.btnClearCookies).setOnClickListener { confirmClearCookies() }
        refreshVoiceUi()
    }

    private fun refreshVoiceUi() {
        val items = VoiceShortcuts.load(this)
        val list = findViewById<LinearLayout>(R.id.voiceList)
        list.removeAllViews()
        findViewById<View>(R.id.tvVoiceEmpty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        for (item in items) list.addView(voiceRow(item))

        val targetId = VoiceShortcuts.assistantTarget(this)
        val targetName = items.firstOrNull { it.id == targetId }?.name
        findViewById<Button>(R.id.btnVoiceTarget).text =
            "语音助手打开时：" + (targetName?.let { "直接进「$it」" } ?: "不特别进入")

        val ref = VoiceShortcuts.lastReferrer(this)
        findViewById<TextView>(R.id.tvVoiceReferrer).text = if (ref.isEmpty())
            "（App 还没被外部唤起过。从语音助手打开一次后，这里会显示是谁唤起的，好确认识别对不对）"
        else "上次由「$ref」唤起 App"
    }

    /** 一条快捷指令：名字 + 网址 + 删除 */
    private fun voiceRow(item: VoiceShortcuts.Item): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 10)
        }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 13f
            setTextIsSelectable(true)
        }
        label.text = "${item.name}\n${item.url}"
        val del = TextView(this).apply {
            text = "删除"
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.purple_500))
            setPadding(28, 12, 28, 12)
            isClickable = true
            isFocusable = true
        }
        del.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("删除语音快捷指令「${item.name}」？")
                .setPositiveButton("删除") { _, _ ->
                    VoiceShortcuts.remove(this, item.id)
                    refreshVoiceUi()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        row.addView(label)
        row.addView(del)
        return row
    }

    private fun addVoiceShortcut() {
        val name = findViewById<TextInputEditText>(R.id.etVoiceName).text?.toString()?.trim() ?: ""
        val url = findViewById<TextInputEditText>(R.id.etVoiceUrl).text?.toString()?.trim() ?: ""
        if (name.isEmpty()) {
            snack("先给它起个名字（你会说的那个词，比如：乘车码）")
            return
        }
        if (url.isEmpty()) {
            snack("要打开的网址还没填")
            return
        }
        val parsed = Uri.parse(url)
        if (parsed.scheme != "http" && parsed.scheme != "https") {
            snack("网址要以 http:// 或 https:// 开头")
            return
        }
        VoiceShortcuts.add(this, name, url)
        findViewById<TextInputEditText>(R.id.etVoiceName).setText("")
        findViewById<TextInputEditText>(R.id.etVoiceUrl).setText("")
        refreshVoiceUi()
        Snackbar.make(findViewById(R.id.btnVoiceAdd), "已加好「$name」：长按桌面图标就能看到", Snackbar.LENGTH_LONG).show()
    }

    private fun chooseVoiceTarget() {
        val items = VoiceShortcuts.load(this)
        if (items.isEmpty()) {
            snack("先加一条语音快捷指令")
            return
        }
        val names = mutableListOf("不特别进入（正常开标签页）")
        names.addAll(items.map { "${it.name}（${it.url}）" })
        val currentId = VoiceShortcuts.assistantTarget(this)
        val checked = items.indexOfFirst { it.id == currentId } + 1   // 0 = 不特别进入
        AlertDialog.Builder(this)
            .setTitle("语音助手打开 App 时")
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                VoiceShortcuts.setAssistantTarget(this, if (which == 0) "" else items[which - 1].id)
                dialog.dismiss()
                refreshVoiceUi()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearCookies() {
        AlertDialog.Builder(this)
            .setTitle("清除所有网站登录状态")
            .setMessage("清掉所有网站的 Cookie，之后这些网站都要重新登录。网页缓存不受影响——要清缓存长按底栏的「刷新」。")
            .setPositiveButton("清除") { _, _ ->
                android.webkit.CookieManager.getInstance().removeAllCookies {
                    android.webkit.CookieManager.getInstance().flush()
                    runOnUiThread { snack("已清除所有网站登录状态") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun snack(msg: String) {
        Snackbar.make(findViewById(R.id.btnSave), msg, Snackbar.LENGTH_LONG).show()
    }

    private fun runDiagnostics() {
        val targetUrl = settingsManager.url.trim()
        if (targetUrl.isEmpty()) {
            Snackbar.make(btnDiagnose, "没有可诊断的网页地址", Snackbar.LENGTH_LONG).show()
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
