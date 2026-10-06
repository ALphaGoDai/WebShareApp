package com.webshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.URL
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var settingsManager: SettingsManager
    private lateinit var webViewContainer: FrameLayout
    private lateinit var tabSwitcher: FrameLayout
    private lateinit var tabGrid: RecyclerView
    private lateinit var tabBadge: TextView

    /** 多标签页：每页一个独立 WebView 和桥接实例，切换时换入换出 */
    private inner class Tab(
        val iface: WebAppInterface,
        val webView: WebView,
        var title: String,
        var url: String?,
        var thumb: Bitmap?
    ) {
        /** 恢复出来的后台页第一次切过去才真正加载（也避免开 App 就并发拉满 6 个页面） */
        var pendingUrl: String? = null
    }

    private val tabs = mutableListOf<Tab>()
    private var currentIndex = -1
    private val tabAdapter by lazy { TabAdapter() }

    /** 当前标签页的 WebView / 桥接（单标签页时代各处直接用的字段都改走这两个入口，行为不变） */
    private val webView: WebView
        get() = tabs[currentIndex].webView
    private val webAppInterface: WebAppInterface
        get() = tabs[currentIndex].iface

    private var currentSharedText: String? = null
    private var currentSharedType: String = "none"

    /** 分享进来的本机文件：页面里的上传入口一开选择器就直接交给它，不再让用户去相册里翻 */
    private var pendingShareUris: List<Uri> = emptyList()

    /** 分享进来的文件在同源虚拟地址上的映射（token -> 文件），供注入脚本取回 */
    private var currentSharedFiles: Map<String, DohWebViewClient.SharedFile> = emptyMap()

    /** 页面加载完成后要注入的「自动上传」脚本；空表示这次分享不需要自动上传 */
    private var pendingAutoUploadJs: String? = null

    private var settingsLauncher: ActivityResultLauncher<Intent>? = null

    /** 用户主动关掉最后一个标签页（=有意识地"清空"）：只有这一种退出才抹掉落盘的标签页 */
    private var clearTabsOnExit = false

    /** 刚从我们自己的页面回来（设置/已下载/选文件）的时刻：别把这次返回当成"回到前台"去刷新 */
    private var ownUiLaunchedAt = 0L

    /** 上一次退到后台的时刻 */
    private var lastStoppedAt = 0L

    /** 冷启动那次已经在 initTabs 里加载过了，不用再"回前台刷新" */
    private var firstStartSeen = false

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

    /** 网页 getUserMedia 要麦克风（学习站点的语音跟读录音）：等运行时权限到手再回答 WebView */
    private var pendingWebPerm: PermissionRequest? = null

    private val micPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingWebPerm ?: return@registerForActivityResult
            pendingWebPerm = null
            if (granted) {
                req.grant(req.resources)
            } else {
                Toast.makeText(this@MainActivity, "没有麦克风权限，网页无法录音", Toast.LENGTH_LONG).show()
                req.deny()
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
        progressBar = findViewById(R.id.progressBar)
        webViewContainer = findViewById(R.id.webViewContainer)
        tabSwitcher = findViewById(R.id.tabSwitcher)
        tabGrid = findViewById(R.id.tabGrid)
        tabBadge = findViewById(R.id.tabBadge)

        initTabs()
        setupBottomBar()
        setupBackNavigation()

        // 系统 TTS：注入脚本里的 speechSynthesis 补丁靠它出声，早点初始化，页面一问就有嗓音
        TtsEngine.init(this)

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

    override fun onStop() {
        super.onStop()
        lastStoppedAt = SystemClock.elapsedRealtime()
        persistTabs(commit = true)   // 退到后台先把标签页记下来（同步落盘，进程随后被杀也带得走）
    }

    override fun onStart() {
        super.onStart()
        maybeRefreshStartPage()
    }

    /**
     * 回到前台时把"起始页"重新拉一遍：进程还活着的话 WebView 里还是上次那份 DOM
     * （站点首页那种列表看着就是旧的），重新加载才看得到新内容。
     * 只刷新起始页这一个站点的标签页——别的页面（日历表单、学习卡片）可能正填着一半，不能动。
     */
    private fun maybeRefreshStartPage() {
        if (!firstStartSeen) {
            firstStartSeen = true          // 冷启动：initTabs 已经把当前页加载起来了
            return
        }
        val own = ownUiLaunchedAt
        ownUiLaunchedAt = 0L
        if (own != 0L && SystemClock.elapsedRealtime() - own < 60_000) return   // 刚从自己的页面回来
        if (SystemClock.elapsedRealtime() - lastStoppedAt < REFRESH_ON_RESUME_AFTER_MS) return
        val tab = tabs.getOrNull(currentIndex) ?: return
        val url = tab.webView.url ?: tab.url ?: return
        if (!isStartPage(url)) return
        Log.i(TAG, "回到前台，刷新起始页 $url")
        tab.webView.reload()
    }

    /** 这个地址是不是设置里那个起始站点的页面（同一主机就算，含它下面的子页面） */
    private fun isStartPage(url: String): Boolean {
        val start = try { URL(settingsManager.url.trim()) } catch (e: Exception) { return false }
        val u = try { URL(url) } catch (e: Exception) { return false }
        return !start.host.isNullOrEmpty() && start.host.equals(u.host, ignoreCase = true)
    }

    /** 记一笔"接下来离开前台是去我们自己的页面"，别在回来时刷新 */
    private fun markOwnUi() {
        ownUiLaunchedAt = SystemClock.elapsedRealtime()
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

    /** 首个标签页 + 切换器网格；有落盘的上次标签页就照它恢复（进程被杀/划掉重开时标签页还在） */
    private fun initTabs() {
        tabGrid.layoutManager = GridLayoutManager(this, 2)
        tabGrid.adapter = tabAdapter
        findViewById<View>(R.id.btnNewTab).setOnClickListener { showAddressBar() }

        val saved = TabStore.load(this)
        if (saved == null) {
            addTab()
            return
        }
        for (s in saved.tabs) {
            val iface = WebAppInterface(this)
            val wv = WebView(this)
            configureWebView(wv, iface)
            val tab = Tab(iface, wv, s.title.ifBlank { getString(R.string.app_name) }, s.url, null)
            tab.pendingUrl = s.url
            tabs.add(tab)
        }
        currentIndex = saved.current
        switchTo(currentIndex)   // 当前页立即按恢复的地址加载，后台页等第一次切过去再加载
    }

    /** 新建一个标签页（建好即成为当前页）；起始地址由调用方决定 */
    private fun addTab(): Tab {
        val iface = WebAppInterface(this)
        val wv = WebView(this)
        configureWebView(wv, iface)
        val tab = Tab(iface, wv, getString(R.string.app_name), null, null)
        tabs.add(tab)
        switchTo(tabs.size - 1)
        return tab
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(wv: WebView, iface: WebAppInterface) {
        // 允许 Chrome 远程调试（chrome://inspect / adb forward），排查网页问题时必需
        WebView.setWebContentsDebuggingEnabled(true)

        val settings = wv.settings
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

        wv.webViewClient = createWebViewClient()
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (view !== tabs.getOrNull(currentIndex)?.webView) return  // 后台标签页的进度不上屏
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
                    markOwnUi()
                    filePickerLauncher.launch(Intent.createChooser(intent, "选择文件"))
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                    false
                }
            }

            /**
             * 网页要麦克风/摄像头：WebView 把决定权交给应用，不实现就一律拒绝
             * （学习站点的语音跟读录音走 getUserMedia，拿到的是 NotAllowedError）。
             * 本应用只放行麦克风；摄像头要 CAMERA 权限，没申请，拒绝。
             */
            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
                if (!req.resources.any { it == audio }) {
                    req.deny()
                    return
                }
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    req.grant(req.resources)
                    return
                }
                pendingWebPerm?.deny()
                pendingWebPerm = req
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            /** 页面放弃/切走了：把还挂着的那次请求作废，别再弹权限 */
            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (pendingWebPerm === request) pendingWebPerm = null
            }
        }

        // 网页里的下载链接（<a download> / Content-Disposition: attachment）：
        // 不设这个监听器，点击就是彻底没反应——WebView 自己不做下载
        wv.setDownloadListener { url, _: String?, contentDisposition, mimeType, contentLength ->
            onDownloadRequested(url, contentDisposition, mimeType, contentLength)
        }

        wv.addJavascriptInterface(iface, "Android")
        iface.attach(wv, settingsManager.dohEnabled, settingsManager.dohUrl)
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
                    markOwnUi()
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
                // 记下标签页标题/地址，切换器卡片和角标用
                tabs.firstOrNull { it.webView === view }?.let { tab ->
                    tab.title = view?.title?.takeIf { it.isNotBlank() } ?: tab.title
                    tab.url = url ?: tab.url
                    persistTabs()
                }
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

    private fun setupBottomBar() {
        // 轻点=普通刷新，长按=强制刷新（清缓存）
        findViewById<View>(R.id.navRefresh).setOnClickListener {
            webView.reload()
        }
        findViewById<View>(R.id.navRefresh).setOnLongClickListener {
            // Visual feedback for long press
            val bar = findViewById<View>(R.id.bottomBar)
            bar.alpha = 0.5f
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
                bar.alpha = 1.0f
            }, 3000)

            true
        }

        findViewById<View>(R.id.navSettings).setOnClickListener {
            markOwnUi()
            settingsLauncher?.launch(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.navDownloads).setOnClickListener {
            markOwnUi()
            startActivity(Intent(this, DownloadsActivity::class.java))
        }

        findViewById<View>(R.id.navTabs).setOnClickListener {
            openTabSwitcher()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 标签页切换器开着时，返回先关它
                if (tabSwitcher.visibility == View.VISIBLE) {
                    hideTabSwitcher()
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun loadUrl(force: Boolean = false) {
        val baseUrl = settingsManager.url.trim()
        if (baseUrl.isEmpty()) {
            showNoUrlMessage()
            return
        }

        // 每次冷启动 handleIntent 都会走到这里：恢复出来的标签页已有自己的地址，
        // 别被设置里的网址冲掉（带分享内容的例外——那本来就是要替换当前页的）
        if (!force && currentSharedText == null && currentTabHasOwnUrl()) return

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

    /** 当前标签页有没有自己的地址（有就说明不是等待首次加载的空标签页） */
    private fun currentTabHasOwnUrl(): Boolean {
        val tab = tabs.getOrNull(currentIndex) ?: return false
        return tab.url != null || tab.pendingUrl != null || tab.webView.url != null
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
        // 设置改动（DoH 等）对每个标签页生效：重建 client、刷新桥接参数；当前页只重载它自己的地址
        // （设置里已经没有网址项了，别再把当前页拽回设置里的默认地址）
        for (tab in tabs) {
            tab.webView.webViewClient = createWebViewClient()
            tab.iface.attach(tab.webView, settingsManager.dohEnabled, settingsManager.dohUrl)
        }
        val cur = tabs.getOrNull(currentIndex) ?: return
        val curUrl = cur.webView.url
        if (curUrl != null && curUrl != "about:blank") {
            cur.webView.reload()
        } else {
            loadUrl(force = true)
        }
    }

    // ---------- 多标签页 ----------

    private fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        if (currentIndex in tabs.indices && currentIndex != index) {
            captureThumb(tabs[currentIndex])
        }
        currentIndex = index
        val tab = tabs[index]
        tab.pendingUrl?.let {
            tab.webView.loadUrl(it)
            tab.pendingUrl = null
        }
        val wv = tab.webView
        if (wv.parent !== webViewContainer) {
            webViewContainer.removeAllViews()
            webViewContainer.addView(
                wv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        updateTabBadge()
        hideTabSwitcher()
        persistTabs()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        if (tabs.size == 1) {
            // 最后一个标签页：关掉就退出应用，并明确清掉落盘记录（下次开是干净的新标签页）
            clearTabsOnExit = true
            finish()
            return
        }
        val wasCurrent = index == currentIndex
        val tab = tabs.removeAt(index)
        webViewContainer.removeView(tab.webView)
        TtsEngine.forget(tab.webView)
        tab.webView.destroy()
        currentIndex = when {
            index < currentIndex -> currentIndex - 1
            wasCurrent -> index.coerceAtMost(tabs.size - 1)
            else -> currentIndex
        }
        if (wasCurrent) {
            val wv = tabs[currentIndex].webView
            webViewContainer.removeAllViews()
            webViewContainer.addView(
                wv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        updateTabBadge()
        tabAdapter.notifyDataSetChanged()
        persistTabs()
    }

    /** 把当前标签页列表写进落盘存储；进程被杀后靠它恢复 */
    private fun persistTabs(commit: Boolean = false) {
        if (clearTabsOnExit) {
            // 用户自己关掉了最后一个标签页（就是明确要清空）：下次开是干净的新标签页。
            // 返回键退出、从最近任务划掉、被系统回收都不清——那几种情况用户要的是"我的页面还在"
            TabStore.clear(this)
            return
        }
        val current = tabs.getOrNull(currentIndex)
        if (current == null || (current.url ?: current.pendingUrl) == null) {
            // 当前页还没地址（刚建的空页/设置里没配网址）：先别覆盖旧记录，等页面起来再写
            return
        }
        val saved = mutableListOf<TabStore.SavedTab>()
        var savedCurrent = -1
        for ((i, tab) in tabs.withIndex()) {
            val raw = tab.url ?: tab.pendingUrl ?: continue
            if (i == currentIndex) savedCurrent = saved.size
            saved.add(TabStore.SavedTab(withoutSharedParam(raw), tab.title))
        }
        if (saved.isNotEmpty() && savedCurrent >= 0) {
            TabStore.save(this, saved, savedCurrent, commit)
        }
    }

    /** ?shared= 是分享时的一次性交接参数（对应的文件登记不跨进程），恢复时带着只会让页面拿到空内容 */
    private fun withoutSharedParam(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url
        val fragIdx = url.indexOf('#').let { if (it >= 0) it else url.length }
        val kept = url.substring(qIdx + 1, fragIdx)
            .split('&')
            .filter { it.substringBefore('=').trim() != "shared" }
        val head = if (kept.isEmpty()) url.substring(0, qIdx) else url.substring(0, qIdx + 1) + kept.joinToString("&")
        return head + url.substring(fragIdx)
    }

    /** 把当前画面缩成一张卡片缩略图（只对挂在前台的 WebView 画，后台页没有画面） */
    private fun captureThumb(tab: Tab) {
        val wv = tab.webView
        val vw = wv.width
        val vh = wv.height
        if (vw == 0 || vh == 0) return
        try {
            val w = 270
            val h = (270f * vh / vw).toInt().coerceIn(240, 540)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(w / vw.toFloat(), h / vh.toFloat())
            wv.draw(canvas)
            tab.thumb = bmp
        } catch (e: Exception) {
            Log.w(TAG, "capture thumb failed: ${e.message}")
        }
    }

    private fun updateTabBadge() {
        tabBadge.text = tabs.size.toString()
    }

    private fun openTabSwitcher() {
        captureThumb(tabs[currentIndex])
        tabAdapter.notifyDataSetChanged()
        tabSwitcher.visibility = View.VISIBLE
    }

    private fun hideTabSwitcher() {
        tabSwitcher.visibility = View.GONE
    }

    /** 新建标签页：弹出地址栏（右侧「访问」），回车或点访问都会跳转；下方列最近访问过的 5 个地址 */
    private fun showAddressBar() {
        val view = LayoutInflater.from(this).inflate(R.layout.view_address_bar, null)
        val input = view.findViewById<EditText>(R.id.addressInput)
        val go = view.findViewById<TextView>(R.id.goBtn)
        val historyBox = view.findViewById<LinearLayout>(R.id.historyList)
        input.setText(settingsManager.url.trim())
        val dialog = AlertDialog.Builder(this)
            .setTitle("新建标签页")
            .setView(view)
            .setNegativeButton("取消", null)
            .create()
        fun launch() {
            val addr = input.text.toString().trim()
            if (addr.isEmpty()) return
            dialog.dismiss()
            openAddressInNewTab(addr)
        }
        fun refreshHistory() {
            historyBox.removeAllViews()
            val items = AddressHistory.recent(this, 5)
            if (items.isEmpty()) {
                historyBox.visibility = View.GONE
                return
            }
            historyBox.visibility = View.VISIBLE
            val pad = (12 * resources.displayMetrics.density).toInt()
            for (addr in items) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val tv = TextView(this).apply {
                    text = addr
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    setPadding(0, pad, 0, pad)
                    background = selectableBackground()
                    setOnClickListener { dialog.dismiss(); openAddressInNewTab(addr) }
                }
                val del = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_nav_close)
                    background = selectableBackgroundBorderless()
                    alpha = 0.55f
                    contentDescription = "删除该记录"
                    setOnClickListener {
                        AddressHistory.remove(this@MainActivity, addr)
                        refreshHistory()
                    }
                }
                row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(
                    del,
                    LinearLayout.LayoutParams(
                        (30 * resources.displayMetrics.density).toInt(),
                        (30 * resources.displayMetrics.density).toInt()
                    )
                )
                historyBox.addView(row)
            }
        }
        refreshHistory()
        go.setOnClickListener { launch() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                launch()
                true
            } else {
                false
            }
        }
        dialog.show()
        input.selectAll()
    }

    /** 取主题里的触摸反馈背景（attr 不能直接 setBackgroundResource，得先解析成 drawable） */
    private fun selectableBackground(): android.graphics.drawable.Drawable? =
        obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { a ->
            try { a.getDrawable(0) } finally { a.recycle() }
        }

    private fun selectableBackgroundBorderless(): android.graphics.drawable.Drawable? =
        obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless)).let { a ->
            try { a.getDrawable(0) } finally { a.recycle() }
        }

    private fun openAddressInNewTab(raw: String) {
        val addr = raw.trim()
        if (addr.isEmpty()) return
        if (tabs.size >= MAX_TABS) {
            Toast.makeText(this, "最多打开 $MAX_TABS 个标签页", Toast.LENGTH_SHORT).show()
            return
        }
        val url = if (addr.startsWith("http://", true) || addr.startsWith("https://", true)) {
            addr
        } else {
            "http://$addr"
        }
        val tab = addTab()   // addTab 会切到新页并关掉切换器
        tab.url = url        // 先记下地址再加载：页面还没加载完进程就被杀也能恢复出来
        tab.iface.setSharedContent("", "none")
        tab.webView.loadUrl(url)
        // 历史只记「从地址栏输入并发起」的地址，网页里随便点的不算
        AddressHistory.record(this, withoutSharedParam(url))
    }

    private inner class TabAdapter : RecyclerView.Adapter<TabVH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabVH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tab, parent, false)
            return TabVH(view)
        }

        override fun getItemCount(): Int = tabs.size

        override fun onBindViewHolder(holder: TabVH, position: Int) {
            val tab = tabs[position]
            holder.title.text = tab.title.ifBlank { "新标签页" }
            if (tab.thumb != null) {
                holder.thumb.setImageBitmap(tab.thumb)
            } else {
                holder.thumb.setImageDrawable(null)
            }
            holder.close.setOnClickListener { closeTab(position) }
            holder.itemView.setOnClickListener { switchTo(position) }
        }
    }

    private class TabVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tabTitle)
        val thumb: ImageView = view.findViewById(R.id.tabThumb)
        val close: ImageButton = view.findViewById(R.id.tabClose)
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

        /** 标签页上限：每个都是一个完整 WebView，内存吃不消太多 */
        private const val MAX_TABS = 6

        /** 退到后台超过这么久再回来，就把起始页重新拉一遍（几秒内的快速来回不折腾） */
        private const val REFRESH_ON_RESUME_AFTER_MS = 3_000L

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
