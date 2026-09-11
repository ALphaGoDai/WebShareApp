package com.webshare.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabSettings: FloatingActionButton
    private lateinit var settingsManager: SettingsManager
    private lateinit var webAppInterface: WebAppInterface

    private var currentSharedText: String? = null
    private var currentSharedType: String = "none"

    private var settingsLauncher: ActivityResultLauncher<Intent>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)
        webAppInterface = WebAppInterface(this)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        fabSettings = findViewById(R.id.fabSettings)

        setupWebView()
        setupFab()
        setupBackNavigation()

        settingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                reloadWebView()
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val type = intent.type
            if (type == "text/plain") {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                currentSharedText = sharedText
                currentSharedType = "text"
            } else if (type?.startsWith("image/") == true) {
                @Suppress("DEPRECATION")
                val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (imageUri != null) {
                    currentSharedText = imageUri.toString()
                    currentSharedType = "image"
                }
            }
        } else {
            currentSharedText = null
            currentSharedType = "none"
        }
        loadUrl()
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        // Disable WebView HTTPS upgrade
        try {
            val experimental = webView.settings.javaClass
                .getMethod("setHttpsUpgradeEnabled", Boolean::class.javaPrimitiveType)
            experimental.invoke(webView.settings, false)
        } catch (e: Exception) {
        }

        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("确定") { _, _ -> result?.confirm() }
                    .setNegativeButton("取消") { _, _ -> result?.cancel() }
                    .show()
                return true
            }
        }

        webView.addJavascriptInterface(webAppInterface, "Android")
    }

    private fun createWebViewClient(): WebViewClient {
        val dohEnabled = settingsManager.dohEnabled
        val dohUrl = settingsManager.dohUrl

        return object : DohWebViewClient(dohEnabled, dohUrl) {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (currentSharedText != null) {
                    val js = buildString {
                        append("if(typeof window.onSharedContent==='function'){")
                        append("window.onSharedContent({")
                        append("type:'").append(escapeJs(currentSharedType)).append("',")
                        append("content:'").append(escapeJs(currentSharedText!!)).append("'")
                        append("});}")
                    }
                    view?.evaluateJavascript(js, null)
                }
            }
        }
    }

    private fun escapeJs(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun setupFab() {
        fabSettings.setOnClickListener {
            settingsLauncher?.launch(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun loadUrl() {
        val baseUrl = settingsManager.url.trim()
        if (baseUrl.isEmpty()) {
            showNoUrlMessage()
            return
        }

        webAppInterface.setSharedContent(currentSharedText ?: "", currentSharedType)

        val finalUrl = if (currentSharedText != null) {
            appendSharedContent(baseUrl, currentSharedText!!)
        } else {
            baseUrl
        }

        webView.loadUrl(finalUrl)
    }

    private fun appendSharedContent(baseUrl: String, shared: String): String {
        // 如果 URL 包含 {shared} 占位符，替换它
        if (baseUrl.contains("{shared}")) {
            return baseUrl.replace("{shared}", Uri.encode(shared))
        }
        // 否则作为查询参数附加
        val separator = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl${separator}shared=${Uri.encode(shared)}"
    }

    private fun reloadWebView() {
        webView.webViewClient = createWebViewClient()
        loadUrl()
    }

    private fun showNoUrlMessage() {
        webView.loadDataWithBaseURL(
            null,
            """
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body {
                        font-family: sans-serif;
                        text-align: center;
                        padding: 60px 24px;
                        color: #333;
                    }
                    h2 { color: #6200EE; }
                    p { color: #666; line-height: 1.6; }
                </style>
            </head>
            <body>
                <h2>未设置网页地址</h2>
                <p>请点击右下角的设置按钮，配置要打开的网页地址。</p>
                <p>设置完成后，您可以将网址或图片分享到本应用，应用会自动将内容传递给您配置的网页进行处理。</p>
            </body>
            </html>
            """.trimIndent(),
            "text/html",
            "utf-8",
            null
        )
    }
}
