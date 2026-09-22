package com.webshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fabSettings: FloatingActionButton
    private lateinit var fabRefresh: FloatingActionButton
    private lateinit var settingsManager: SettingsManager
    private lateinit var webAppInterface: WebAppInterface

    private var currentSharedText: String? = null
    private var currentSharedType: String = "none"

    /** 分享进来的本机文件：页面里的上传入口一开选择器就直接交给它，不再让用户去相册里翻 */
    private var pendingShareUris: List<Uri> = emptyList()

    /** 分享进来的文件在同源虚拟地址上的映射（token -> 文件），供注入脚本取回 */
    private var currentSharedFiles: Map<String, DohWebViewClient.SharedFile> = emptyMap()

    /** 页面加载完成后要注入的「自动上传」脚本；空表示这次分享不需要自动上传 */
    private var pendingAutoUploadJs: String? = null

    private var settingsLauncher: ActivityResultLauncher<Intent>? = null

    /** 网页里 <input type="file"> 触发的选择回调，选中后必须回填 */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** 等目录选好后才能开始的下载 */
    private var pendingDownload: PendingDownload? = null

    private data class PendingDownload(val url: String, val name: String, val mime: String)

    private val dirPickerLauncher: ActivityResultLauncher<Uri?> = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val req = pendingDownload ?: return@registerForActivityResult
        pendingDownload = null
        if (treeUri == null) return@registerForActivityResult

        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
        }

        val location = SaveLocationStore(this).remember(treeUri)
        startDownload(req, location)
    }

    private val filePickerLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@registerForActivityResult

        if (result.resultCode != RESULT_OK) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris.add(it) }
            }
        }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }

        if (uris.isEmpty()) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        // 记下用户选了哪些文件：上传时 FormData 里的文件要按 文件名+大小 找回真实内容
        webAppInterface.registerPickedFiles(uris)
        callback.onReceiveValue(uris.toTypedArray())
    }

    private val notificationPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 分享进来的相册文件要读得到才有得上传：13+ 按类型要 READ_MEDIA_*，更早要 READ_EXTERNAL_STORAGE */
    private val mediaPermissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { granted -> granted == false }) {
                Toast.makeText(
                    this@MainActivity,
                    "没有读取相册的权限，站点拿不到分享的文件",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val shimJs: String by lazy {
        try {
            assets.open("shim.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) { "" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)
        webAppInterface = WebAppInterface(this)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        fabSettings = findViewById(R.id.fabSettings)
        fabRefresh = findViewById(R.id.fabRefresh)

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
        val action = intent?.action
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            val type = intent.type ?: ""
            if (type.startsWith("text/")) {
                currentSharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                currentSharedType = "text"
                pendingShareUris = emptyList()
                currentSharedFiles = emptyMap()
                pendingAutoUploadJs = null
            } else {
                val uris = sharedStreams(intent)
                if (uris.isNotEmpty()) {
                    // 登记一份：页面里的 <input type="file"> 打开时直接回填，multipart 上传也靠这份登记找回内容
                    pendingShareUris = uris
                    currentSharedText = uris.first().toString()
                    currentSharedType = type.substringBefore('/').ifEmpty { "file" }
                    ensureMediaReadPermission(type)
                    webAppInterface.registerPickedFiles(uris)
                    prepareAutoUpload(uris)
                } else {
                    currentSharedText = null
                    currentSharedType = "none"
                    pendingShareUris = emptyList()
                    currentSharedFiles = emptyMap()
                    pendingAutoUploadJs = null
                }
            }
        } else {
            currentSharedText = null
            currentSharedType = "none"
            pendingShareUris = emptyList()
            currentSharedFiles = emptyMap()
            pendingAutoUploadJs = null
        }
        loadUrl()
    }

    /** ACTION_SEND（单个）与 ACTION_SEND_MULTIPLE（相册多选）都从 EXTRA_STREAM 取 uri */
    private fun sharedStreams(intent: Intent): List<Uri> {
        return when (val raw = intent.extras?.get(Intent.EXTRA_STREAM)) {
            is Uri -> listOf(raw)
            is List<*> -> raw.filterIsInstance<Uri>()
            else -> emptyList()
        }
    }

    /** 分享进来的文件要读得到才上传得了：Android 13+ 按类型要 READ_MEDIA_*，更早版本是 READ_EXTERNAL_STORAGE */
    private fun ensureMediaReadPermission(mimeType: String) {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (mimeType.startsWith("image/")) wanted.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (mimeType.startsWith("video/")) wanted.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (mimeType.startsWith("audio/")) wanted.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            wanted.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) mediaPermissionLauncher.launch(missing.toTypedArray())
    }

    /** 页面这次要的类型和分享进来的文件对得上吗？对不上就走普通选择器，免得往输入框里塞错东西 */
    private fun shareMatchesAccept(uris: List<Uri>, params: WebChromeClient.FileChooserParams?): Boolean {
        val accepts = params?.acceptTypes?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
        if (accepts.isNullOrEmpty() || accepts.contains("*/*")) return true

        val sharedTypes = uris.mapNotNull { contentResolver.getType(it)?.lowercase() }
        if (sharedTypes.isEmpty()) return true      // 问不出类型（file:// 之类）就别拦着

        for (accept in accepts) {
            if (accept.startsWith(".")) continue    // 只认扩展名的限定理解不了，交给通配分支
            val family = accept.substringBefore('/')
            for (shared in sharedTypes) {
                if (shared == accept) return true
                if (accept.endsWith("/*") && shared.substringBefore('/') == family) return true
            }
        }
        return false
    }

    /**
     * 「分享完自动保存」：网页读不到 content://，也没法在没有用户手势时打开文件选择器，
     * 所以这一步只能由 App 做——把文件挂到同源虚拟地址上，注入脚本取回来包成 File
     * 塞进页面的 <input type="file"> 再触发 change，走的还是站点自己的上传逻辑
     * （保存目录、进度提示、失败处理都不变）。页面里没有上传入口就什么都不做，
     * 保留"点上传时直接回填分享文件"那条老路。
     */
    private fun prepareAutoUpload(uris: List<Uri>) {
        val files = linkedMapOf<String, DohWebViewClient.SharedFile>()
        val specs = mutableListOf<String>()
        for (uri in uris) {
            var name: String? = null
            var size = -1L
            try {
                contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "shared file metadata failed: $uri (${e.message})")
            }
            val fileName = name ?: uri.lastPathSegment ?: "shared-file"
            val mime = try { contentResolver.getType(uri) } catch (e: Exception) { null }
            val resolver = contentResolver
            val token = UUID.randomUUID().toString().replace("-", "")
            files[token] = DohWebViewClient.SharedFile(fileName, mime ?: guessMime(fileName), size) {
                try { resolver.openInputStream(uri) } catch (e: Exception) { null }
            }
            specs.add(
                "{url:'${DohWebViewClient.SHARED_PATH_PREFIX}$token'," +
                    "name:'${escapeJs(fileName)}',mime:'${escapeJs(mime ?: guessMime(fileName))}'}"
            )
        }
        if (files.isEmpty()) {
            currentSharedFiles = emptyMap()
            pendingAutoUploadJs = null
            return
        }
        currentSharedFiles = files
        pendingAutoUploadJs = "(function(){var specs=[${specs.joinToString(",")}];$AUTO_UPLOAD_JS})();"
        Log.i(TAG, "auto upload prepared: ${files.values.joinToString { it.name }}")
    }

    private fun guessMime(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "3gp" -> "video/3gpp"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            else -> "application/octet-stream"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // 允许 Chrome 远程调试（chrome://inspect / adb forward），排查网页问题时必需
        WebView.setWebContentsDebuggingEnabled(true)

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
        // 页面里的播放器在自动重试/转码完成后会直接 play()，此时用户手势可能已过期
        settings.mediaPlaybackRequiresUserGesture = false

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

            /** 网页 <input type="file">（本应用里的「上传」按钮）：不实现就完全没反应 */
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)

                // 刚分享进来的文件：页面一开选择器就直接回填，用户不用再到相册里翻一遍
                val shared = pendingShareUris
                if (shared.isNotEmpty() && shareMatchesAccept(shared, fileChooserParams)) {
                    pendingShareUris = emptyList()
                    fileChooserCallback = null
                    webAppInterface.registerPickedFiles(shared)
                    filePathCallback?.onReceiveValue(shared.toTypedArray())
                    Toast.makeText(
                        this@MainActivity,
                        if (shared.size == 1) "已使用分享的文件" else "已使用分享的 ${shared.size} 个文件",
                        Toast.LENGTH_SHORT
                    ).show()
                    return true
                }

                fileChooserCallback = filePathCallback

                val mimeTypes = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotBlank() && it != "*/*" }
                    ?.toTypedArray()

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (mimeTypes.isNullOrEmpty()) "*/*" else mimeTypes[0]
                    if (!mimeTypes.isNullOrEmpty()) {
                        putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                    }
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                return try {
                    filePickerLauncher.launch(Intent.createChooser(intent, "选择文件"))
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                    false
                }
            }
        }

        // 网页里的下载链接（<a download> / Content-Disposition: attachment）：
        // 不设这个监听器，点击就是彻底没反应——WebView 自己不做下载
        webView.setDownloadListener { url, _: String?, contentDisposition, mimeType, contentLength ->
            onDownloadRequested(url, contentDisposition, mimeType, contentLength)
        }

        webView.addJavascriptInterface(webAppInterface, "Android")
        webAppInterface.attach(webView, settingsManager.dohEnabled, settingsManager.dohUrl)
    }

    /** 点击网页下载按钮：先问保存到哪个目录，再交给前台服务走 App 自己的 DNS/TLS 通道下载 */
    private fun onDownloadRequested(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ) {
        Log.d(TAG, "download: url=$url cd=$contentDisposition mime=$mimeType len=$contentLength")

        val scheme = try { Uri.parse(url).scheme?.lowercase() } catch (e: Exception) { null }
        if (scheme != "http" && scheme != "https") {
            // blob:/data: 这类地址要从网页里取内容，当前不走这条链路
            Toast.makeText(
                this,
                "该链接不是普通文件地址（$scheme），暂不支持保存",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val name = DownloadNaming.fileName(url, contentDisposition, mimeType)
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val request = PendingDownload(url, name, mime)

        val store = SaveLocationStore(this)
        DownloadUi.showSaveDialog(
            activity = this,
            store = store,
            fileName = name,
            sizeText = if (contentLength > 0) DownloadService.fmtSize(contentLength) else null,
            onStart = { location -> startDownload(request, location) },
            onChooseOther = {
                pendingDownload = request
                try {
                    dirPickerLauncher.launch(null)
                } catch (e: Exception) {
                    pendingDownload = null
                    Toast.makeText(this, "无法打开目录选择器", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun startDownload(request: PendingDownload, location: SaveLocation) {
        val store = SaveLocationStore(this)
        store.lastUsedId = location.id

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (e: Exception) {
            }
        }

        DownloadService.start(
            context = this,
            url = request.url,
            name = request.name,
            mime = request.mime,
            treeUri = location.treeUri?.toString(),
            referer = webView.url
        )
        Toast.makeText(this, "开始下载：${request.name}", Toast.LENGTH_SHORT).show()
    }

    private fun createWebViewClient(): WebViewClient {
        val dohEnabled = settingsManager.dohEnabled
        val dohUrl = settingsManager.dohUrl

        val configuredUrl = settingsManager.url.trim()
        var forceHttpHost = ""
        var configuredHost = ""
        try {
            val uri = Uri.parse(configuredUrl)
            configuredHost = uri.host ?: ""
            if (configuredUrl.startsWith("http://")) {
                forceHttpHost = configuredHost
            }
        } catch (e: Exception) {
        }

        val appVersion = try {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "" }

        val activityContext = this
        return object : DohWebViewClient(
            dohEnabled, dohUrl, forceHttpHost, configuredHost, appVersion, shimJs,
            cacheDir = cacheDir,
            onNotice = { note ->
                runOnUiThread { Toast.makeText(activityContext, note, Toast.LENGTH_LONG).show() }
            }
        ) {
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
                // 分享进来的文件：页面一就绪就把文件塞给它的上传入口，用户不用再点一下
                val auto = pendingAutoUploadJs
                if (auto != null && view != null) {
                    pendingAutoUploadJs = null
                    view.evaluateJavascript(auto, null)
                    view.postDelayed({ reportAutoUpload(view) }, 1800)
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

        // Refresh: tap = normal refresh, long press = force refresh (clear cache)
        fabRefresh.setOnClickListener {
            webView.reload()
        }

        fabRefresh.setOnLongClickListener {
            // Visual feedback for long press
            fabRefresh.alpha = 0.5f
            Toast.makeText(this, "正在清除缓存并刷新...", Toast.LENGTH_SHORT).show()

            // Clear all caches
            webView.clearCache(true)
            webView.clearHistory()
            // Also clear cookies for a full force refresh
            android.webkit.CookieManager.getInstance().removeAllCookies { }
            android.webkit.CookieManager.getInstance().flush()

            // Reload with cache bypass
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.reload()

            // Reset cache mode after a delay
            webView.postDelayed({
                webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                fabRefresh.alpha = 1.0f
            }, 3000)

            true
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

        // 分享文件的虚拟地址挂在当前 client 上（client 在改设置后会重建，所以要随 load 一起挂）
        (webView.webViewClient as? DohWebViewClient)?.sharedFiles = currentSharedFiles

        webView.loadUrl(finalUrl)
    }

    /** 读回注入脚本的结果：走通就轻描一句，没走通提醒用户手动点页面的上传入口 */
    private fun reportAutoUpload(view: WebView) {
        view.evaluateJavascript("(window.__webshareAutoUploadResult||'')") { raw ->
            val result = raw?.trim('"') ?: ""
            Log.i(TAG, "auto upload result: $result")
            when (result) {
                "dispatched", "hook" ->
                    Toast.makeText(this, "已把分享的文件交给站点自动上传", Toast.LENGTH_SHORT).show()
                "running" ->
                    Toast.makeText(this, "正在把分享的文件交给站点…", Toast.LENGTH_SHORT).show()
                else ->
                    Toast.makeText(this, "没能自动上传（$result），点页面上的上传入口即可", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun appendSharedContent(baseUrl: String, shared: String): String {
        if (baseUrl.contains("{shared}")) {
            return baseUrl.replace("{shared}", Uri.encode(shared))
        }
        val separator = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl${separator}shared=${Uri.encode(shared)}"
    }

    private fun reloadWebView() {
        webView.webViewClient = createWebViewClient()
        webAppInterface.attach(webView, settingsManager.dohEnabled, settingsManager.dohUrl)
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

    companion object {
        private const val TAG = "WebShareApp"

        /**
         * 注入到页面里的自动上传脚本（前面会拼上 specs 数组）。取回分享文件的字节包成 File，
         * 优先走页面自己声明的 window.webshareAutoUpload(files)，否则塞给第一个
         * <input type="file"> 并触发 change —— 站点里的 onchange 处理器（比如 send 站点的
         * uploadPicked）就会照常跑完整套上传。结果写进 window.__webshareAutoUploadResult
         * 供 App 读回来提示用户。
         */
        private val AUTO_UPLOAD_JS = """
            window.__webshareAutoUploadResult='running';
            (async function(){
              try{
                var files=[];
                for(var i=0;i<specs.length;i++){
                  var s=specs[i];
                  var r=await fetch(s.url,{cache:'no-store'});
                  if(!r.ok) throw new Error('HTTP '+r.status);
                  var b=await r.blob();
                  files.push(new File([b], s.name, {type:s.mime}));
                }
                if(typeof window.webshareAutoUpload==='function'){
                  window.webshareAutoUpload(files);
                  window.__webshareAutoUploadResult='hook';
                  return;
                }
                var inputs=document.querySelectorAll('input[type=file]');
                if(!inputs.length){ window.__webshareAutoUploadResult='no-input'; return; }
                var pick=inputs[0];
                var family=(files[0].type.split('/')[0]||'');
                for(var j=0;j<inputs.length;j++){
                  var acc=inputs[j].getAttribute('accept')||'';
                  if(acc && acc.indexOf('*/*')<0 && acc.indexOf(family)>=0){ pick=inputs[j]; break; }
                }
                var dt=new DataTransfer();
                for(var k=0;k<files.length;k++) dt.items.add(files[k]);
                pick.files=dt.files;
                pick.dispatchEvent(new Event('change',{bubbles:true}));
                window.__webshareAutoUploadResult='dispatched';
              }catch(e){
                window.__webshareAutoUploadResult='error: '+((e&&e.message)||e);
              }
            })();
        """.trimIndent()
    }
}
