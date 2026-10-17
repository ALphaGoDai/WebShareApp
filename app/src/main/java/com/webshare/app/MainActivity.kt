package com.webshare.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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

    /** 底栏：左边标签页首字母（可左右翻），右边三条线按钮弹出 刷新/已下载/设置 */
    private lateinit var tabStrip: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabScrollLeft: View
    private lateinit var tabScrollRight: View
    private lateinit var menuButton: View
    private lateinit var navMenu: View
    private lateinit var navMenuScrim: View

    /** 标签条内容的签名（首字母/选中项没变就不重建，免得电视上焦点被冲掉） */
    private var tabStripSignature = ""
    /** 上次把哪一页的圆片滚进可视区：只在真的换了页时滚，别把用户手动翻的位置拉回来 */
    private var revealedTabIndex = -1

    /** 底部那条「此网站请求打开 App」提示（网页要跳 douban:// 之类时出现） */
    private lateinit var openAppBar: View
    private lateinit var openAppIcon: ImageView
    private lateinit var openAppName: TextView

    // ---------- 电视 / 遥控器 ----------

    /** 电视（Leanback 桌面、无触摸屏）：开虚拟光标，方向键交给页面，工具栏靠长按 OK 过去 */
    private var tvMode = false

    /** 页面里的输入框正在编辑（shim.js 上报）：方向键归 WebView，别拿去挪光标 */
    private val pageEditing = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 长按 OK：等够时间就把焦点交给工具栏（而不是点击） */
    private var centerLongPress: Runnable? = null
    private var centerLongPressed = false

    /**
     * 电视上「网页」是一个控件：焦点刚落到网页上只是选中它（不显示光标、不响应方向键），
     * 再按一次 OK 才进入页面内的光标模式。手机没有这个状态，始终为 true。
     */
    private var tvPageActive = false
    private var tvPageHintShown = false
    /** 电视上从网页按返回落到工具栏后，再按一次返回才退出（避免按一下返回就把 App 退了） */
    private var tvBackArmedForExit = false
    /** 电视切换器里当前焦点所在的卡片（「编辑 / 关闭」操作条作用于它） */
    private var switcherFocusPosition = 0

    /** 遥控器操作提示只弹一次（每进程） */
    private var tvHintShown = false

    /** 网页全屏（<video> 全屏 / 自定义全屏元素）时挂在最上层的视图 */
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

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

        /**
         * 用户长按标签标题设置的备注名（空 = 没设）。它同时是语音快捷指令的名字：
         * 「打开<应用名><备注名>」直接切到这一页，所以设了备注名的标签页会多出一条桌面快捷方式。
         */
        var alias: String? = null

        /** 底栏圆片上显示的字（用户单独设的，和标题/别名无关；空 = 默认取标题首个字） */
        var badge: String? = null
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

    /** 攒一下再落盘的 Cookie 写盘任务（见 scheduleCookieFlush） */
    private val flushCookiesRunnable = Runnable { flushCookies() }

    private var settingsLauncher: ActivityResultLauncher<Intent>? = null

    /** 用户主动关掉最后一个标签页（=有意识地"清空"）：只有这一种退出才抹掉落盘的标签页 */
    private var clearTabsOnExit = false

    /** 刚从我们自己的页面回来（设置/已下载/选文件）的时刻：别把这次返回当成"回到前台"去刷新 */
    private var ownUiLaunchedAt = 0L

    /** 上一次退到后台的时刻 */
    private var lastStoppedAt = 0L

    /** 冷启动那次已经在 initTabs 里加载过了，不用再"回前台刷新" */
    private var firstStartSeen = false

    /** 「到某地自动打开」：一次进程只判一次（冷启动那一刻），判过就不再问位置 */
    private var geoRuleChecked = false
    /** 还没等到位置的异步判定：目标标签页 / 这一页本来就在加载的地址 / 起始时刻 */
    private var geoPendingTab: Tab? = null
    /** 等位置的时候这一页本来就要加载的地址（冷启动恢复的标签页会自己加载一次）——
     *  这次加载不算"用户自己动了"，只有跳到别的地址才算 */
    private var geoPendingKnownUrls: List<String> = emptyList()
    private var geoPendingAt = 0L
    /** 用户在这次冷启动里已经自己动过（点了标签页/开了新标签页/页面跳走了）→ 不抢 */
    private var userTouchedSinceStart = false

    /** 网页请求打开别的 App：待打开的 Intent + 显示用信息（这条提示条的状态） */
    private var pendingAppIntent: Intent? = null
    private var pendingAppFallback: String? = null
    private var pendingAppUrl = ""

    /** 同一条链接短时间内重复请求（页面点一下可能连发几次），只弹一次条 */
    private var lastAppPromptUrl = ""
    private var lastAppPromptAt = 0L

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
        tvMode = detectTvMode()
        if (tvMode) {
            // 电视上看视频/网页别让系统到点休眠；电视本来也没有触摸屏
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)
        progressBar = findViewById(R.id.progressBar)
        webViewContainer = findViewById(R.id.webViewContainer)
        tabSwitcher = findViewById(R.id.tabSwitcher)
        tabGrid = findViewById(R.id.tabGrid)
        tabStrip = findViewById(R.id.tabStrip)
        tabScroll = findViewById(R.id.tabScroll)
        tabScrollLeft = findViewById(R.id.tabScrollLeft)
        tabScrollRight = findViewById(R.id.tabScrollRight)
        menuButton = findViewById(R.id.menuButton)
        navMenu = findViewById(R.id.navMenu)
        navMenuScrim = findViewById(R.id.navMenuScrim)
        openAppBar = findViewById(R.id.openAppBar)
        openAppIcon = findViewById(R.id.openAppIcon)
        openAppName = findViewById(R.id.openAppName)

        initTabs()
        setupBottomBar()
        setupOpenAppBar()
        setupBackNavigation()
        if (tvMode) setupTvMode()

        // 系统 TTS：注入脚本里的 speechSynthesis 补丁靠它出声，早点初始化，页面一问就有嗓音
        TtsEngine.init(this)

        maybeAskNotificationPermission()

        // 语音快捷指令同步成桌面快捷方式（设置里改了名字/网址，这里每次启动再对齐一次）
        VoiceShortcuts.sync(this)

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
        flushCookies()               // 登录状态同理：不落盘的话进程被杀就丢，下次还得重新登录
    }

    override fun onDestroy() {
        centerLongPress?.let { tabs.getOrNull(currentIndex)?.webView?.removeCallbacks(it) }
        centerLongPress = null
        super.onDestroy()
    }

    // ---------- 电视 / 遥控器 ----------

    /**
     * 是不是电视：Leanback 桌面 / 电视类型设备。也可以在命令行用
     * `am start … --ez tv_mode true` 在手机上模拟（测试用，记住后一直生效）。
     */
    private fun detectTvMode(): Boolean {
        if (intent?.getBooleanExtra("tv_mode", false) == true) {
            getSharedPreferences("webshare_tv", MODE_PRIVATE)
                .edit().putBoolean("forced", true).apply()
        }
        return TvMode.isTv(this)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 电视布局：加高底栏、放大字号、给所有工具按钮加焦点框，并让网页先拿到焦点 */
    private fun setupTvMode() {
        val bar = findViewById<View>(R.id.bottomBar)
        bar.layoutParams = bar.layoutParams.apply { height = dp(64) }

        // 底栏：左右翻页箭头、三条线按钮，以及它弹出的三个菜单项，遥控器都要能选中
        val navIds = intArrayOf(
            R.id.tabScrollLeft, R.id.tabScrollRight, R.id.menuButton,
            R.id.menuRefresh, R.id.menuDownloads, R.id.menuSettings
        )
        for (id in navIds) {
            val item = findViewById<View>(id)
            item.isFocusable = true
            item.foreground = ContextCompat.getDrawable(this, R.drawable.bg_tv_focus)
        }
        // 菜单面板在电视上离底栏（64dp）再高一点，别贴着标签条
        (navMenu.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = dp(76)
        for (id in intArrayOf(R.id.menuRefresh, R.id.menuDownloads, R.id.menuSettings)) {
            setMenuItemTextSize(findViewById<View>(id))
        }
        (menuButton as? ViewGroup)?.let { group ->
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is ImageView) {
                    val lp = child.layoutParams
                    lp.width = dp(28)
                    lp.height = dp(28)
                    child.layoutParams = lp
                }
            }
        }
        // 菜单项之间上下走，左右不跑出去；最后一项按「下」回到三条线按钮
        bindMenuFocusChain()

        // 浮条往上挪一点，别被加高后的底栏盖住
        val stack = findViewById<View>(R.id.popupStack)
        (stack.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = dp(76)

        // 浮条上的按钮也参与焦点：长按 OK / 菜单键切到工具栏后能按到
        for (id in intArrayOf(R.id.openAppGo, R.id.openAppClose)) {
            val v = findViewById<View>(id)
            v.isFocusable = true
            v.foreground = ContextCompat.getDrawable(this, R.drawable.bg_tv_focus)
        }
        findViewById<View>(R.id.btnNewTab).apply {
            isFocusable = true
            foreground = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tv_focus)
        }

        tabs.getOrNull(currentIndex)?.let {
            bindTvFocusTargets(it.webView)
            it.webView.requestFocus()
        }
        maybeShowTvHint()
    }

    private fun setMenuItemTextSize(item: View) {
        val group = item as? ViewGroup ?: return
        for (i in 0 until group.childCount) {
            when (val child = group.getChildAt(i)) {
                is TextView -> child.textSize = 16f
                is ImageView -> {
                    val lp = child.layoutParams
                    lp.width = dp(26)
                    lp.height = dp(26)
                    child.layoutParams = lp
                }
            }
        }
    }

    private fun bindMenuFocusChain() {
        val refresh = findViewById<View>(R.id.menuRefresh)
        val downloads = findViewById<View>(R.id.menuDownloads)
        val settings = findViewById<View>(R.id.menuSettings)
        refresh.nextFocusUpId = R.id.menuButton
        refresh.nextFocusDownId = R.id.menuDownloads
        downloads.nextFocusUpId = R.id.menuRefresh
        downloads.nextFocusDownId = R.id.menuSettings
        settings.nextFocusUpId = R.id.menuDownloads
        settings.nextFocusDownId = R.id.menuButton
        for (v in arrayOf(refresh, downloads, settings)) {
            v.nextFocusLeftId = v.id
            v.nextFocusRightId = v.id
        }
    }

    /** 工具栏每一项按「上」都回到网页；网页按「下」落到当前标签圆片（没有就落到三条线） */
    private fun bindTvFocusTargets(wv: WebView) {
        val wvId = wv.id
        if (wvId == View.NO_ID) return
        for (id in intArrayOf(
            R.id.tabScrollLeft, R.id.tabScrollRight, R.id.menuButton,
            R.id.menuRefresh, R.id.menuDownloads, R.id.menuSettings,
            R.id.openAppGo
        )) {
            findViewById<View>(id).nextFocusUpId = wvId
        }
        for (i in 0 until tabStrip.childCount) {
            tabStrip.getChildAt(i).nextFocusUpId = wvId
        }
        val down = currentChipId() ?: R.id.menuButton
        wv.nextFocusDownId = down
        findViewById<View>(R.id.openAppGo).nextFocusDownId = down
        bindTabStripFocus()
    }

    private fun currentChipId(): Int? =
        tabStrip.getChildAt(currentIndex)?.takeIf { it.id != View.NO_ID }?.id

    /**
     * 标签圆片之间显式串成左右一条链：从三条线按钮按左能一路走到每一页，
     * 避免 HorizontalScrollView 自己把焦点吃掉（真机上左右只滚动、选不中圆片）。
     */
    private fun bindTabStripFocus() {
        if (!tvMode) return
        val n = tabStrip.childCount
        if (n == 0) {
            menuButton.nextFocusLeftId = R.id.menuButton
            return
        }
        for (i in 0 until n) {
            val chip = tabStrip.getChildAt(i)
            if (chip.id == View.NO_ID) chip.id = View.generateViewId()
        }
        val first = tabStrip.getChildAt(0).id
        val last = tabStrip.getChildAt(n - 1).id
        for (i in 0 until n) {
            val chip = tabStrip.getChildAt(i)
            chip.nextFocusLeftId = when {
                i > 0 -> tabStrip.getChildAt(i - 1).id
                tabScrollLeft.visibility == View.VISIBLE -> R.id.tabScrollLeft
                else -> chip.id
            }
            chip.nextFocusRightId = when {
                i < n - 1 -> tabStrip.getChildAt(i + 1).id
                tabScrollRight.visibility == View.VISIBLE -> R.id.tabScrollRight
                else -> R.id.menuButton
            }
        }
        tabScrollLeft.nextFocusLeftId = R.id.tabScrollLeft
        tabScrollLeft.nextFocusRightId = first
        tabScrollRight.nextFocusLeftId = last
        tabScrollRight.nextFocusRightId = R.id.menuButton
        menuButton.nextFocusLeftId =
            if (tabScrollRight.visibility == View.VISIBLE) R.id.tabScrollRight else last
        menuButton.nextFocusRightId = R.id.menuButton
        if (navMenu.visibility == View.VISIBLE) {
            // 菜单开着时保持吸在菜单里（标题变化会重跑这里）
            menuButton.nextFocusLeftId = R.id.menuButton
            menuButton.nextFocusUpId = R.id.menuSettings
        }
    }

    /** 进入/退出页面内的光标模式（电视）：光标只在「已按 OK 进入网页」后才显示 */
    private fun setTvPageActive(active: Boolean) {
        if (!tvMode) return
        tvPageActive = active
        tabs.getOrNull(currentIndex)?.webView?.let { wv ->
            wv.evaluateJavascript("window.__wsTvShow&&window.__wsTvShow($active)", null)
        }
    }

    private fun activateTvPage() {
        if (tvPageActive) return
        tvBackArmedForExit = false
        setTvPageActive(true)
        if (!tvPageHintShown) {
            tvPageHintShown = true
            Toast.makeText(
                this,
                "已进入网页：方向键移动圆点，OK 点击；长按 OK / 菜单键回工具栏",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 长按 OK / 菜单键：光标让位，焦点交给工具栏（落在当前标签圆片上） */
    private fun focusNativeControls() {
        val wv = tabs.getOrNull(currentIndex)?.webView ?: return
        tvBackArmedForExit = false
        setTvPageActive(false)
        blurPageEditing(wv)
        val target = when {
            openAppBar.visibility == View.VISIBLE -> findViewById<View>(R.id.openAppGo)
            else -> tabStrip.getChildAt(currentIndex) ?: menuButton
        }
        target.requestFocus()
        Toast.makeText(
            this,
            "已切到工具栏：左右选标签页，OK 切换；再往右是三条线菜单；按 ↑ 回到网页",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 结束网页里的输入状态：收起输入法、让焦点离开输入框，光标重新能用。
     * 不做的话焦点会一直留在 <input> 上，方向键被当成"移光标"永远出不来。
     */
    private fun blurPageEditing(wv: WebView) {
        if (!pageEditing.get()) return
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(wv.windowToken, 0)
        } catch (_: Exception) {
        }
        wv.evaluateJavascript(
            "(function(){var a=document.activeElement;" +
                "if(a&&a.blur)a.blur();else if(window.getSelection)window.getSelection().removeAllRanges();})()",
            null
        )
        pageEditing.set(false)
    }

    /**
     * 电视没有实体键盘：原生 EditText 通过遥控器拿到焦点后，主动把电视输入法叫出来。
     * （只靠系统默认行为时，有些电视要把 OK 按两次才弹，或者干脆不弹。）
     */
    private fun attachTvIme(edit: EditText) {
        if (!tvMode) return
        edit.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                v.postDelayed({
                    try {
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                    } catch (e: Exception) {
                        Log.w(TAG, "原生输入框弹输入法失败: ${e.message}")
                    }
                }, 150)
            }
        }
    }

    /** 打开对话框后把焦点和输入法直接送到输入框（电视上默认焦点常落在按钮上） */
    private fun showTvImeNow(v: View) {
        if (!tvMode) return
        v.postDelayed({
            v.requestFocus()
            try {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) {
                Log.w(TAG, "对话框输入框弹输入法失败: ${e.message}")
            }
        }, 250)
    }

    /** 网页里的输入框：叫电视输入法；第一下常常只唤起窗口不显示，隔一会儿再补 */
    private fun showImeForWebView(wv: WebView, attempt: Int = 0) {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT)
            if (!imm.isActive(wv) && attempt < 2) {
                wv.postDelayed({ showImeForWebView(wv, attempt + 1) }, 450)
            }
        } catch (e: Exception) {
            Log.w(TAG, "网页输入框弹输入法失败: ${e.message}")
        }
    }

    private fun maybeShowTvHint() {
        if (tvHintShown) return
        tvHintShown = true
        Toast.makeText(
            this,
            "电视遥控：方向键先选中按钮/网页，再按 OK 执行；网页要先按一次 OK 进入，方向键才移动光标\n" +
                "长按 OK / 菜单键切到工具栏 · 缓存视频：菜单里长按「已下载」· 播放页：←/→ 快退快进 10 秒 · OK 暂停",
            Toast.LENGTH_LONG
        ).show()
    }

    /** 本机离线播放页（127.0.0.1/p/…）：方向键留给页面自己（快进退/音量） */
    private fun isLocalPlayerPage(url: String?): Boolean =
        url != null && url.startsWith("http://127.0.0.1:") && url.contains("/p/")

    override fun onResume() {
        super.onResume()
        if (!tvMode) return
        val wv = tabs.getOrNull(currentIndex)?.webView ?: return
        tvPageActive = false
        tvBackArmedForExit = false
        wv.post {
            if (hasWindowFocus() && !pageEditing.get()) wv.requestFocus()
        }
    }

    /**
     * 遥控器按键：
     * - 焦点刚落到网页上＝选中「网页」这个控件：不显示光标，方向键照常移焦点（↓ 回工具栏），
     *   按一次 OK 才进入页面内的光标模式
     * - 页面内：方向键移动虚拟光标；OK 在光标处点击；长按 OK 切到工具栏
     * - 菜单键（ACTION MENU）在网页和工具栏之间来回切
     * - 播放/快进/快退键直接控制页面里的 video
     * - 页面里正在输入文字（输入框有焦点）时，方向键原样交给 WebView
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!tvMode) return super.dispatchKeyEvent(event)
        // 网页全屏时把按键全交给全屏视图（视频控件自己会处理方向键/OK）
        if (customView != null) return super.dispatchKeyEvent(event)
        // 标签页切换器是整屏浮层：按键归浮层。焦点没进去时先塞到「新建标签页」，
        // 否则下面的网页逻辑会把方向键吃掉（旧版就是这样，遥控器根本走不进卡片）。
        if (tabSwitcher.visibility == View.VISIBLE) {
            if (!tabSwitcher.hasFocus()) {
                findViewById<View>(R.id.btnNewTab).requestFocus()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val key = event.keyCode

        if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_INFO) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (navMenu.visibility == View.VISIBLE) {
                    hideNavMenu()
                    return true
                }
                val wv = tabs.getOrNull(currentIndex)?.webView
                if (wv != null) {
                    if (wv.hasFocus()) {
                        focusNativeControls()
                    } else {
                        setTvPageActive(false)
                        wv.requestFocus()
                    }
                }
            }
            return true
        }

        val wv = tabs.getOrNull(currentIndex)?.webView ?: return super.dispatchKeyEvent(event)

        // 媒体键：页面上有视频就控制它
        val mediaAction = when (key) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "toggle"
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "forward"
            KeyEvent.KEYCODE_MEDIA_REWIND -> "rewind"
            else -> null
        }
        if (mediaAction != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                wv.evaluateJavascript("window.__wsTvMedia&&window.__wsTvMedia('$mediaAction')", null)
            }
            return true
        }

        // 对话框/输入法/别的窗口在前台时不抢键；输入框在编辑时方向键归网页
        if (!hasWindowFocus() || !wv.hasFocus()) return super.dispatchKeyEvent(event)
        if (pageEditing.get()) return super.dispatchKeyEvent(event)
        if (isLocalPlayerPage(wv.url)) return super.dispatchKeyEvent(event)

        // 网页只是被选中、还没进入：方向键不移动光标、也不让 WebView 拿去滚页面；
        // 「下」离开网页回到工具栏，OK 才进入页面
        if (!tvPageActive) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        (tabStrip.getChildAt(currentIndex) ?: menuButton).requestFocus()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        activateTvPage()
                    }
                    return true
                }
                else -> return super.dispatchKeyEvent(event)
            }
        }

        when (key) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val dx = when (key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> -1
                        KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                        else -> 0
                    }
                    val dy = when (key) {
                        KeyEvent.KEYCODE_DPAD_UP -> -1
                        KeyEvent.KEYCODE_DPAD_DOWN -> 1
                        else -> 0
                    }
                    // 按住方向键连续移动时加速：前 6 步细调，之后每步 3 倍
                    val mult = if (event.repeatCount >= 6) 3 else 1
                    wv.evaluateJavascript(
                        "window.__wsTvMove&&window.__wsTvMove(${dx * mult},${dy * mult})", null
                    )
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            centerLongPressed = false
                            val runnable = Runnable {
                                centerLongPressed = true
                                focusNativeControls()
                            }
                            centerLongPress = runnable
                            // 550ms：比普通点按长、又不至于让手慢的用户误触
                            wv.postDelayed(runnable, 550)
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        centerLongPress?.let { wv.removeCallbacks(it) }
                        centerLongPress = null
                        if (!centerLongPressed) {
                            tapAtCursor(wv)
                        }
                        centerLongPressed = false
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- 登录状态（Cookie） ----------

    /**
     * Cookie 落盘。WebView 平时把 Cookie 攒在内存里，进程被系统回收就没了——
     * 用户看到的就是"刚登录过的站点下次打开又要重新登录"。页面加载完和退到后台各写一次。
     */
    private fun flushCookies() {
        try {
            android.webkit.CookieManager.getInstance().flush()
        } catch (e: Exception) {
            Log.w(TAG, "cookie flush 失败: ${e.message}")
        }
    }

    /** 页面加载完别立刻 flush（连跳几页会写好几遍），攒一下再落盘 */
    private fun scheduleCookieFlush() {
        webView.removeCallbacks(flushCookiesRunnable)
        webView.postDelayed(flushCookiesRunnable, 1200)
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

    /** 是不是同一个页面（忽略结尾斜杠、片段、大小写；WebView 报的地址常和存的不完全一样） */
    private fun samePage(a: String, b: String): Boolean {
        fun norm(s: String) = s.substringBefore('#').trim().trimEnd('/')
        return norm(a).equals(norm(b), ignoreCase = true)
    }

    /** 记一笔"接下来离开前台是去我们自己的页面"，别在回来时刷新 */
    private fun markOwnUi() {
        ownUiLaunchedAt = SystemClock.elapsedRealtime()
    }


    /**
     * Android 13+ 的通知权限：下载完成通知、网页自己的通知都靠它。第一次进 App 问一次
     * （记在 prefs 里，用户拒了就不再追问——想开的话设置页有「系统通知设置」直接跳过去）。
     */
    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences("webshare_notify", MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        try {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            Log.w(TAG, "请求通知权限失败: ${e.message}")
        }
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action

        // 语音快捷方式 / 外部工具唤起：webshare://voice/<id> → 直达那条配置的页面
        // （id 以 tab: 开头的是"标签页备注名"，直接切到那个标签页）
        val data = intent?.data
        if (action == Intent.ACTION_VIEW && data?.scheme == VoiceShortcuts.SCHEME) {
            // webshare://play/<缓存id>：缓存列表点「播放」，就在本浏览器里打开离线播放页
            if (data.host == "play") {
                val id = data.lastPathSegment?.toLongOrNull()
                val entry = id?.let { VideoCacheStore.find(this, it) }
                val url = entry?.let { LocalMediaServer.playerUrl(this, it) }
                if (url != null) {
                    Log.i(TAG, "打开离线播放页：${entry!!.title} → $url")
                    openUrlInTab(url)
                } else {
                    Toast.makeText(this, "这份缓存已经不在了", Toast.LENGTH_SHORT).show()
                }
                return
            }
            val seg = data.lastPathSegment
            if (seg != null && seg.startsWith(VoiceShortcuts.TAB_PREFIX)) {
                val alias = seg.removePrefix(VoiceShortcuts.TAB_PREFIX)
                Log.i(TAG, "快捷方式唤起：标签页备注「$alias」")
                if (openAliasTab(alias)) return
            } else {
                val item = seg?.let { VoiceShortcuts.find(this, it) }
                if (item != null) {
                    Log.i(TAG, "快捷方式唤起：「${item.name}」→ ${item.url}")
                    openUrlInTab(item.url)
                    return
                }
                Log.w(TAG, "快捷方式找不到对应的配置: $data")
            }
        }

        // 语音助手（小艺 / 华为快捷指令）只会"打开应用"时，靠 referrer 认出它，直达设置里指定的那页
        // （桌面点开图标也是 MAIN 且没有 referrer → 正常开标签页，不受影响）
        if ((action == null || action == Intent.ACTION_MAIN) && data == null &&
            intent?.getBooleanExtra(WebNotifications.EXTRA_FROM_NOTIFICATION, false) != true &&
            maybeVoiceAssistantJump()
        ) return

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
        // 「到某地自动打开」：只有"用户打开 App"这一种（点图标/最近任务）才判，而且每次打开都重判一次
        // ——走到公交站时 App 多半还在后台，从桌面再点一下就该进设定页。分享进来的内容要占着当前页、
        // 点通知是想看那条通知的页面，都不判；语音/深链在上面的分支里已经有目标页了（都 return 了）。
        val fromNotification =
            intent?.getBooleanExtra(WebNotifications.EXTRA_FROM_NOTIFICATION, false) == true
        if (currentSharedText == null && !fromNotification) {
            geoRuleChecked = false             // 这次"打开"重新判一次位置
            userTouchedSinceStart = false
            maybeApplyGeoRule()
        }
        loadUrl()
    }

    /**
     * 语音助手唤起检测：小艺、华为「快捷指令」这类能配的只有"打开应用"一个动作，
     * 所以认出处是它们就够了——设置里指定了"语音助手打开时直接进"的那条，就直达那页
     * （网页仍在自己这个浏览器里打开，自定义 DNS 照常生效）。桌面图标点开时 referrer 是桌面，不跳。
     */
    private fun maybeVoiceAssistantJump(): Boolean {
        val ref = launchReferrer()
        val pkg = ref?.authority?.takeIf { it.isNotBlank() } ?: ref?.host ?: ""
        if (pkg.isNotEmpty()) VoiceShortcuts.setLastReferrer(this, pkg)
        Log.i(TAG, "本次打开来源: ${pkg.ifEmpty { "(没有 referrer)" }}")
        if (pkg.isEmpty() || !VoiceShortcuts.looksLikeAssistant(pkg)) return false
        val targetId = VoiceShortcuts.assistantTarget(this)
        if (targetId.isEmpty()) return false
        if (targetId.startsWith(VoiceShortcuts.TAB_PREFIX)) {
            val alias = targetId.removePrefix(VoiceShortcuts.TAB_PREFIX)
            Log.i(TAG, "语音助手唤起，切到备注「$alias」的标签页")
            return openAliasTab(alias)
        }
        val item = VoiceShortcuts.find(this, targetId) ?: return false
        Log.i(TAG, "语音助手唤起，直达「${item.name}」${item.url}")
        openUrlInTab(item.url)
        return true
    }

    /** 这次是谁把 App 叫起来的（语音助手会在 referrer 里留下包名） */
    @Suppress("DEPRECATION")
    private fun launchReferrer(): Uri? {
        try {
            (intent?.getParcelableExtra(Intent.EXTRA_REFERRER) as? Uri)?.let { return it }
        } catch (e: Exception) {
        }
        return try {
            referrer
        } catch (e: Exception) {
            null
        }
    }

    /** 打开一条指定网址：已经有同一地址的标签页就切过去刷新，否则新开一个（不动其它标签页） */
    private fun openUrlInTab(url: String) {
        val exist = tabs.indexOfFirst { it.url == url || it.pendingUrl == url }
        if (exist >= 0) {
            switchTo(exist)
            tabs[exist].webView.reload()
            return
        }
        val tab = addTab()
        tab.url = url
        tab.webView.loadUrl(url)
    }

    // ---------- 到某地自动打开（走到某个坐标附近，打开 App 就进设定那页） ----------

    /**
     * 冷启动这一次判一次位置（见 GeoRule）：
     * ① 手上就有 10 分钟内的位置 → 立刻判，命中就把这一页换成设定页；
     * ② 没有可用位置 → 先照常打开起始页，同时短超时要一次，拿到了、确实在范围内、
     *    而且用户还没自己动过（没开切换器/没开新标签页/页面也没跳去别处）→ 再切过去。
     * 没权限、系统定位没开、或者一次进程已经判过，就什么都不做。
     */
    private fun maybeApplyGeoRule() {
        if (geoRuleChecked) return
        geoRuleChecked = true
        val rule = settingsManager.geoRule() ?: return
        if (!GeoRule.hasPermission(this)) {
            Log.i(TAG, "到点自动打开：没有位置权限，跳过")
            return
        }
        val baseUrl = settingsManager.url.trim()
        val tab = tabs.getOrNull(currentIndex) ?: return
        // baseUrl 只用于日志/排错提示（见 openGeoRulePage 附近的判定日志）
        Log.i(TAG, "到点自动打开：起始页 $baseUrl，规则 ${GeoRule.formatCoord(rule.lat)},${GeoRule.formatCoord(rule.lng)} 半径 ${rule.radiusMeters} 米")

        val known = GeoRule.bestLastKnown(this)
        if (known != null) {
            val d = GeoRule.distanceMeters(known.latitude, known.longitude, rule.lat, rule.lng)
            if (!GeoRule.accurateEnough(known, rule.radiusMeters)) {
                Log.i(TAG, "到点自动打开：位置精度 ${GeoRule.describeAccuracy(known)} 比范围还差，不判")
                return
            }
            Log.i(TAG, "到点自动打开：现在距目标 ${GeoRule.describe(d)}（阈值 ${rule.radiusMeters} 米，位置来自 ${known.provider}，精度 ${GeoRule.describeAccuracy(known)}，${GeoRule.describeAge(GeoRule.ageMs(known))}）")
            if (d <= rule.radiusMeters) openGeoRulePage(rule, d, GeoRule.ageMs(known))
            return
        }
        if (!GeoRule.locationServiceUsable(this)) {
            Log.i(TAG, "到点自动打开：系统定位没开，跳过")
            return
        }
        Log.i(TAG, "到点自动打开：没有一分钟内的定位，当场要一次（最多 6 秒）")
        geoPendingTab = tab
        geoPendingKnownUrls = listOfNotNull(tab.webView.url, tab.url, tab.pendingUrl)
        geoPendingAt = SystemClock.elapsedRealtime()
        GeoRule.requestSingle(this, 6000) { res ->
            val loc = res.loc
            if (loc == null) {
                if (res.staleAgeMs > 0) {
                    Log.i(TAG, "到点自动打开：只拿到 ${GeoRule.describeAge(res.staleAgeMs)}，不是当前坐标，不判")
                } else {
                    Log.i(TAG, "到点自动打开：没拿到位置，按不在范围内处理")
                }
                geoPendingTab = null
                geoPendingKnownUrls = emptyList()
                return@requestSingle
            }
            val d = GeoRule.distanceMeters(loc.latitude, loc.longitude, rule.lat, rule.lng)
            Log.i(TAG, "到点自动打开：拿到位置，距目标 ${GeoRule.describe(d)}（来自 ${loc.provider}，精度 ${GeoRule.describeAccuracy(loc)}，${GeoRule.describeAge(GeoRule.ageMs(loc))}）")
            val pending = geoPendingTab
            geoPendingTab = null
            geoPendingKnownUrls = emptyList()
            if (pending == null) return@requestSingle
            if (!GeoRule.accurateEnough(loc, rule.radiusMeters)) {
                Log.i(TAG, "到点自动打开：精度太差，这次不判")
                return@requestSingle
            }
            if (d > rule.radiusMeters) return@requestSingle
            // 用户已经自己动过就别抢他的页面
            if (userTouchedSinceStart) {
                Log.i(TAG, "到点自动打开：用户已经自己动过，不切了")
                return@requestSingle
            }
            if (tabs.getOrNull(currentIndex) !== pending) return@requestSingle
            if (SystemClock.elapsedRealtime() - geoPendingAt > 11000) return@requestSingle
            openGeoRulePage(rule, d, GeoRule.ageMs(loc))
        }
    }

    /** 命中：还在起始页/空页就地把这一页换成设定页，否则新开一个（同地址已经在就切过去） */
    private fun openGeoRulePage(rule: GeoRule.Rule, meters: Double, fixAgeMs: Long) {
        Toast.makeText(
            this,
            "到设定范围了（距目标 ${GeoRule.describe(meters)}，${GeoRule.describeAge(fixAgeMs)}），" +
                    "打开「${geoRuleLabel(rule)}」",
            Toast.LENGTH_LONG
        ).show()
        val tab = tabs.getOrNull(currentIndex)
        val empty = tab != null && !currentTabHasOwnUrl() &&
                (tab.webView.url ?: "").isEmpty() && (tab.pendingUrl ?: "").isEmpty()
        if (tab != null && empty) {
            tab.url = rule.url
            tab.pendingUrl = rule.url
            tab.webView.loadUrl(rule.url)
            persistTabs()
            return
        }
        openUrlInTab(rule.url)
    }

    /** 提示语里的名字：能对上语音快捷指令/标签页备注名就用那个名字，否则用主机名 */
    private fun geoRuleLabel(rule: GeoRule.Rule): String {
        VoiceShortcuts.load(this).firstOrNull { it.url == rule.url }?.let { return it.name }
        TabStore.load(this)?.tabs.orEmpty()
            .firstOrNull { it.url == rule.url && it.alias.isNotEmpty() }
            ?.let { return it.alias }
        return try { java.net.URL(rule.url).host ?: rule.url } catch (e: Exception) { rule.url }
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
        findViewById<View>(R.id.btnNewTab).setOnClickListener {
            userTouchedSinceStart = true   // 用户自己开新标签页了，到点自动打开别再插手
            showAddressBar()
        }

        val saved = TabStore.load(this)
        if (saved == null) {
            addTab()
            syncVoiceShortcuts()
            return
        }
        for (s in saved.tabs) {
            val iface = WebAppInterface(this)
            val wv = WebView(this)
            configureWebView(wv, iface)
            val tab = Tab(iface, wv, s.title.ifBlank { getString(R.string.app_name) }, s.url, null)
            tab.pendingUrl = s.url
            tab.alias = s.alias.takeIf { it.isNotEmpty() }
            tab.badge = s.badge.takeIf { it.isNotEmpty() }
            tabs.add(tab)
        }
        currentIndex = saved.current
        switchTo(currentIndex)   // 当前页立即按恢复的地址加载，后台页等第一次切过去才加载
        syncVoiceShortcuts()
    }

    /**
     * 把各标签页的备注名同步成语音快捷方式（名字就是备注名，点了/说了就切到那一页）。
     * 没有备注名的标签页不占快捷方式名额；名额满了由 VoiceShortcuts 按"备注名优先"截断。
     */
    private fun syncVoiceShortcuts() {
        val entries = tabs.mapNotNull { t ->
            val alias = t.alias?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = t.url ?: t.pendingUrl ?: return@mapNotNull null
            VoiceShortcuts.Item(VoiceShortcuts.TAB_PREFIX + alias, alias, url)
        }
        VoiceShortcuts.sync(this, entries)
    }

    /**
     * 切到备注名为 alias 的标签页。标签页已经关掉/记录被清时，从落盘记录里找回它的网址新开一页，
     * 找不到就提示一句（并告诉用户备注名是在哪儿设的）。
     */
    private fun openAliasTab(alias: String): Boolean {
        val idx = tabs.indexOfFirst { it.alias == alias }
        if (idx >= 0) {
            switchTo(idx)
            return true
        }
        val saved = TabStore.load(this)?.tabs?.firstOrNull { it.alias == alias }
        if (saved != null) {
            val tab = addTab()
            tab.alias = alias
            tab.url = saved.url
            tab.webView.loadUrl(saved.url)
            syncVoiceShortcuts()
            return true
        }
        Toast.makeText(
            this,
            "没有备注为「$alias」的标签页——长按标签标题栏可以设备注名",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    /** 长按标签卡片标题栏：编辑备注名（语音快捷指令用）+ 底栏圆片显示的「页面字」 */
    private fun editTabAlias(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(Color.parseColor("#5F6368"))
            setPadding(0, dp(10), 0, dp(2))
        }

        val aliasInput = EditText(this).apply {
            setSingleLine(true)
            setText(tab.alias ?: "")
            hint = "例如：乘车码"
        }
        val badgeInput = EditText(this).apply {
            setSingleLine(true)
            setText(tab.badge ?: "")
            hint = tabInitial(tab)   // 没设时这里就是默认会显示的字
        }
        box.addView(label("备注名（语音快捷指令用，留空恢复原名）"))
        box.addView(aliasInput)
        box.addView(label("页面字（底栏圆片只显示第一个字，切换器卡片标题前也会用【】标出；留空＝按标题取首字、卡片不显示括号）"))
        box.addView(badgeInput)
        attachTvIme(aliasInput)
        attachTvIme(badgeInput)

        val dialog = AlertDialog.Builder(this)
            .setTitle("编辑标签页")
            .setMessage(
                "备注名同时是语音快捷指令的名字：对小艺说「打开" + getString(R.string.app_name) +
                    "」＋备注名就能直达这一页；页面字用于底栏圆片，也会显示在切换器卡片标题前，和网页标题无关。"
            )
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val aliasText = aliasInput.text.toString().trim()
                val badgeText = badgeInput.text.toString().trim()
                tab.alias = aliasText.ifEmpty { null }
                tab.badge = badgeText.firstOrNull { !it.isWhitespace() }?.toString()
                persistTabs()
                syncVoiceShortcuts()
                tabAdapter.notifyItemChanged(index)
                updateTabStrip()
                Toast.makeText(
                    this,
                    "已保存：底栏圆片显示「${tabInitial(tab)}」",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        // 电视上打开就把焦点和输入法送到第一个输入框，不用再用遥控器找
        showTvImeNow(aliasInput)
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

        // 电视遥控：WebView 自己得能拿焦点，方向键才会落到页面上。
        // 焦点刚落到网页上只是「选中这个控件」（画一圈焦点框），再按 OK 才显示光标进入页面。
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        if (wv.id == View.NO_ID) wv.id = View.generateViewId()
        if (tvMode) {
            wv.foreground = ContextCompat.getDrawable(this, R.drawable.bg_tv_page_focus)
        }
        wv.setOnFocusChangeListener { _, hasFocus ->
            if (tvMode) {
                if (!hasFocus) tvPageActive = false
                if (hasFocus) tvBackArmedForExit = false
                val show = hasFocus && tvPageActive
                wv.evaluateJavascript("window.__wsTvShow&&window.__wsTvShow($show)", null)
            }
        }
        iface.tvEditingListener = { editing ->
            if (tabs.getOrNull(currentIndex)?.iface === iface) pageEditing.set(editing)
        }

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

        // 登录状态（Cookie）要跨重启记住：① 明确接受 Cookie；② 第三方 Cookie 也接受——
        // 政务/统一认证这类站点登录时要在多个域名/子域之间跳转，默认关掉会让登录态存不下来
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

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

            /**
             * 网页里的视频/元素请求全屏：把它挂到窗口最上层铺满整屏。
             * 不实现的话，电视上点视频的「全屏」按钮完全没反应（手机上也是）。
             */
            override fun onShowCustomView(
                view: View?,
                callback: WebChromeClient.CustomViewCallback?
            ) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                val decor = window.decorView as? FrameLayout
                if (decor == null || view == null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                decor.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                hideOpenAppBar()
                // 全屏时把系统栏也藏起来（电视上没有，手机上有效）
                @Suppress("DEPRECATION")
                decor.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            }

            override fun onHideCustomView() {
                hideCustomViewNow()
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
            },
            onOpenExternalApp = { url, direct ->
                runOnUiThread { if (direct) openAppDirect(url) else promptOpenApp(url) }
            }
        ) {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // 换页了：上一页的输入框编辑状态作废
                pageEditing.set(false)
                // 页面换了一页，上一条"打开 App"的提示就过期
                hideOpenAppBar()
                // 换页了：上一页发现的视频不再算这一页的（长按「已下载」只认当前页）
                MediaWatch.clearView(view)
                MediaWatch.setPageUrl(view, url)   // 记地址（拦截线程不能碰 WebView，只能在这儿记）
                // 「到点自动打开」还在等位置时，页面要是自己跳去了别的站点（用户点了别的链接），
                // 说明人已经在看别的东西了——记一笔，等会儿判定命中也不抢这一页。
                // 恢复的标签页冷启动那次自己的加载不算（地址本来就在加载的那个）。
                val pending = geoPendingTab
                if (pending != null && view === pending.webView && url != null && !isStartPage(url) &&
                    geoPendingKnownUrls.none { samePage(it, url) }
                ) {
                    userTouchedSinceStart = true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                scheduleCookieFlush()   // 刚登录/刚拿到会话的 Cookie 及时落盘
                // 电视：页面就绪后把光标画出来（前提是焦点确实在网页上）
                if (tvMode && view != null) {
                    val bf = view.copyBackForwardList()
                    val items = (0 until bf.size).joinToString(" | ") {
                        val item = bf.getItemAtIndex(it)
                        "${item.url}@${it}"
                    }
                    Log.i(
                        TAG,
                        "tv page: url=$url bfSize=${bf.size} bfIndex=${bf.currentIndex} " +
                            "canGoBack=${view.canGoBack()} [$items]"
                    )
                    val show = if (view.hasFocus() && tvPageActive) "true" else "false"
                    view.evaluateJavascript("window.__wsTvShow&&window.__wsTvShow($show)", null)
                }
                // 记下标签页标题/地址，切换器卡片和角标用
                tabs.firstOrNull { it.webView === view }?.let { tab ->
                    tab.title = view?.title?.takeIf { it.isNotBlank() } ?: tab.title
                    tab.url = url ?: tab.url
                    updateTabStrip()   // 底栏圆片取标题首字，换页后跟着变
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

    // ---------- 缓存视频（长按底栏「已下载」：把这一页的视频存进浏览器，之后离线看） ----------

    /**
     * 底栏「已下载」长按：从当前这一页发现的视频源里挑一个开始缓存。
     * 一个候选直接开始，多个列出来让用户选，没有就提示先去播一下。
     */
    private fun startCacheFromPage() {
        val tab = tabs.getOrNull(currentIndex)
        val list = if (tab == null) emptyList() else MediaWatch.forView(tab.webView)
        if (list.isEmpty()) {
            Toast.makeText(
                this,
                "这一页还没发现视频：先在网页里点播放，再长按「已下载」",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (list.size == 1) {
            startCache(list[0])
            return
        }
        val labels = list.map { item ->
            val size = if (item.size > 0) " · ${DownloadService.fmtSize(item.size)}" else ""
            "${item.baseName}（${item.kindLabel}$size）\n${item.shortUrl}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("缓存哪个视频？")
            .setItems(labels) { _, which -> startCache(list[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startCache(item: MediaWatch.Item) {
        val tab = tabs.getOrNull(currentIndex)
        val title = pickVideoTitle(item, tab)
        Toast.makeText(
            this,
            "开始缓存：$title\n进度在通知栏，缓存完在「已下载 → 缓存视频」里离线看",
            Toast.LENGTH_LONG
        ).show()
        Log.i(TAG, "开始缓存视频：$title ${item.kind} ${item.url}")
        VideoCacheService.start(this, item, title)
    }

    /**
     * 缓存文件叫什么名字：优先用视频自己的文件名，但播放列表那种（index.m3u8 / playlist）
     * 认不出内容，就改用标签页标题（一般是视频名）。
     */
    private fun pickVideoTitle(item: MediaWatch.Item, tab: Tab?): String {
        val base = item.baseName
        val generic = base.length < 4 || base.matches(Regex("\\d+")) ||
                base.lowercase() in setOf("index", "playlist", "master", "manifest", "video", "media", "hls", "out", "v")
        val tabName = (tab?.alias?.takeIf { !it.isNullOrBlank() } ?: tab?.title)?.trim()
        return if (generic && !tabName.isNullOrBlank()) tabName.take(60) else base
    }

    // ---------- 网页请求打开别的 App（Chrome 的「此网站请求打开 App」） ----------

    private fun setupOpenAppBar() {
        findViewById<View>(R.id.openAppGo).setOnClickListener { openPendingApp() }
        findViewById<View>(R.id.openAppClose).setOnClickListener { hideOpenAppBar() }
    }

    /** 网页要打开手机上另一个 App（douban://…）：弹提示条，点「打开」才真的跳 */
    private fun promptOpenApp(url: String) {
        val target = ExternalApp.resolve(this, url) ?: return
        val now = SystemClock.elapsedRealtime()
        // 一次点击页面可能连发几条同样的跳转，只提示一次（不然闪个不停）
        if (url == lastAppPromptUrl && now - lastAppPromptAt < 3000) return
        lastAppPromptUrl = url
        lastAppPromptAt = now
        pendingAppUrl = url
        pendingAppIntent = target.intent
        pendingAppFallback = target.fallbackUrl
        openAppName.text = target.label
        if (target.icon != null) {
            openAppIcon.setImageDrawable(target.icon)
        } else {
            // 认不出目标 App（没装 / 被包可见性挡住）：用通用图标，点开时会再提示一句
            openAppIcon.setImageResource(R.drawable.ic_nav_open_app)
        }
        openAppBar.visibility = View.VISIBLE
        Log.i(TAG, "网页请求打开 App：$url → ${target.label}（图标=${target.icon != null}）")
    }

    /**
     * 用户主动点了一个我们认识的 App scheme（douban:// 之类）：不弹确认条，直接交给系统。
     * 系统自己会弹「选择打开方式」（装了多个或需要确认时），手机上没装则是那句 toast。
     */
    private fun openAppDirect(url: String) {
        val target = ExternalApp.resolve(this, url)
        if (target == null) { promptOpenApp(url); return }   // 解析不出来才退回确认条
        val now = SystemClock.elapsedRealtime()
        // 一次点击页面可能连发两条同样的跳转（onclick + 兜底定时器），别开两次
        if (url == lastAppPromptUrl && now - lastAppPromptAt < 3000) return
        lastAppPromptUrl = url
        lastAppPromptAt = now
        hideOpenAppBar()
        pendingAppUrl = url
        pendingAppIntent = target.intent
        pendingAppFallback = target.fallbackUrl
        Log.i(TAG, "直接打开 App（不弹确认条）：$url → ${target.label}")
        openPendingApp()
    }

    private fun hideOpenAppBar() {
        if (this::openAppBar.isInitialized) openAppBar.visibility = View.GONE
        pendingAppUrl = ""
        pendingAppIntent = null
        pendingAppFallback = null
    }

    private fun openPendingApp() {
        val intent = pendingAppIntent
        val fallback = pendingAppFallback
        hideOpenAppBar()
        if (intent == null) return
        if (ExternalApp.launch(this, intent)) return
        Toast.makeText(this, "手机上没装能打开这个链接的应用", Toast.LENGTH_LONG).show()
        // intent:// 允许网页带一个"打不开就用浏览器打开"的兜底网址
        if (!fallback.isNullOrBlank()) {
            Log.i(TAG, "App 打不开，改用兜底网址：$fallback")
            webView.loadUrl(fallback)
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
        // 右下角三条线：竖向弹出「刷新 / 已下载 / 设置」
        menuButton.setOnClickListener {
            markOwnUi()
            if (navMenu.visibility == View.VISIBLE) hideNavMenu() else showNavMenu()
        }
        // 点菜单外面（遮罩）收起
        navMenuScrim.setOnClickListener { hideNavMenu() }

        // 标签页多了：左右两侧出现翻页箭头，标签条本身也能横向滑
        tabScroll.setOnScrollChangeListener { _, _, _, _, _ -> updateTabScrollButtons() }
        tabScrollLeft.setOnClickListener {
            markOwnUi()
            scrollTabStrip(-1)
        }
        tabScrollRight.setOnClickListener {
            markOwnUi()
            scrollTabStrip(1)
        }
        tabScroll.post { updateTabScrollButtons() }

        // 轻点=普通刷新，长按=强制刷新（清缓存，登录状态保留）
        findViewById<View>(R.id.menuRefresh).setOnClickListener {
            hideNavMenu()
            reloadCurrentPage()
        }
        findViewById<View>(R.id.menuRefresh).setOnLongClickListener {
            hideNavMenu()
            clearCacheAndReload()
            true
        }

        findViewById<View>(R.id.menuDownloads).setOnClickListener {
            hideNavMenu()
            markOwnUi()
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        // 长按「已下载」= 缓存这一页在放的视频（不再每页弹浮条）
        findViewById<View>(R.id.menuDownloads).setOnLongClickListener {
            hideNavMenu()
            markOwnUi()
            startCacheFromPage()
            true
        }

        findViewById<View>(R.id.menuSettings).setOnClickListener {
            hideNavMenu()
            markOwnUi()
            settingsLauncher?.launch(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun reloadCurrentPage() {
        if (currentIndex !in tabs.indices) return
        webView.reload()
    }

    private fun clearCacheAndReload() {
        if (currentIndex !in tabs.indices) return
        val bar = findViewById<View>(R.id.bottomBar)
        bar.alpha = 0.5f
        Toast.makeText(this, "正在清除缓存并刷新（登录状态保留）...", Toast.LENGTH_SHORT).show()

        webView.clearCache(true)
        webView.clearHistory()
        // 注意：这里不清 Cookie —— Cookie 是各网站的登录状态，不是缓存。
        // 原来顺手 removeAllCookies 会让用户"强制刷新一下就退出所有网站登录"。
        // 真要清登录状态去 设置 → 清除所有网站登录状态（Cookie）。
        flushCookies()

        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.reload()
        webView.postDelayed({
            webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            bar.alpha = 1.0f
        }, 3000)
    }

    private fun showNavMenu() {
        if (navMenu.visibility == View.VISIBLE) return
        navMenu.visibility = View.VISIBLE
        navMenuScrim.visibility = View.VISIBLE
        navMenu.alpha = 0f
        navMenu.translationY = dp(8).toFloat()
        navMenu.animate().alpha(1f).translationY(0f).setDuration(140).start()
        if (tvMode) {
            // 菜单开着时，三条线按钮按「上」回到菜单最后一项，左右也被菜单吸住，别跳回网页/标签条
            menuButton.nextFocusUpId = R.id.menuSettings
            menuButton.nextFocusLeftId = R.id.menuButton
            menuButton.nextFocusRightId = R.id.menuButton
            findViewById<View>(R.id.menuRefresh).post {
                findViewById<View>(R.id.menuRefresh).requestFocus()
            }
        }
    }

    private fun hideNavMenu() {
        if (navMenu.visibility != View.VISIBLE) return
        navMenu.visibility = View.GONE
        navMenuScrim.visibility = View.GONE
        if (tvMode) {
            val wvId = tabs.getOrNull(currentIndex)?.webView?.id ?: View.NO_ID
            if (wvId != View.NO_ID) menuButton.nextFocusUpId = wvId
            bindTabStripFocus()
            if (menuButton.isShown) menuButton.requestFocus()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 网页全屏时，返回先退出全屏
                if (customView != null) {
                    hideCustomViewNow()
                    return
                }
                // 右下角菜单开着时，返回先收起它
                if (navMenu.visibility == View.VISIBLE) {
                    hideNavMenu()
                    return
                }
                // 标签页切换器开着时，返回先关它
                if (tabSwitcher.visibility == View.VISIBLE) {
                    hideTabSwitcher()
                    return
                }
                // 电视上正在输入框里打字：返回先退出输入状态（收键盘、光标恢复可用）
                if (tvMode && pageEditing.get()) {
                    blurPageEditing(webView)
                    return
                }
                // 电视上光标已经在页面里（鼠标模式）：返回先退出光标模式，回到「网页被选中」，
                // 不要把整个页面/应用退掉——在页面上按返回就退出应用，用户会以为标签丢了
                if (tvMode && tvPageActive) {
                    setTvPageActive(false)
                    return
                }
                // 电视上焦点在工具栏（不在网页）时：
                // - 从网页按返回落到工具栏的（tvBackArmedForExit）：再按一次返回才退出应用
                // - 长按 OK / 菜单键进来的：返回把焦点还给网页（只选中，不显示光标）
                if (tvMode && !webView.hasFocus()) {
                    if (tvBackArmedForExit) {
                        tvBackArmedForExit = false
                        Log.i(TAG, "back: 工具栏再按一次返回，退出应用（保留 ${tabs.size} 个标签页）")
                        finish()
                        return
                    }
                    setTvPageActive(false)
                    webView.requestFocus()
                    return
                }
                // 注意：有些 WebView 版本 canGoBack() 会撒谎（列表明明有上一页却一直 false，
                // 系统 WebView 132 上就复现了），goBackOrForward(-1) 也跟着不动。
                // 可靠的办法是让页面自己 history.back()（Chromium 的 Blink 历史是准的）；
                // 万一页面没有 JS（PDF 之类），再退回原生 goBackOrForward。
                val bf = webView.copyBackForwardList()
                if (bf.currentIndex > 0) {
                    Log.i(TAG, "back: 回上一页（index=${bf.currentIndex}/${bf.size}）")
                    val before = webView.url
                    webView.evaluateJavascript("history.back()", null)
                    webView.postDelayed({
                        if (webView.url == before) {
                            Log.i(TAG, "back: history.back() 没动静，退回原生 goBackOrForward(-1)")
                            webView.goBackOrForward(-1)
                        }
                    }, 450)
                } else if (tvMode) {
                    // 电视：没有上一页时先回工具栏，不直接退出——焦点落到当前标签圆片
                    // （圆片现在有醒目的焦点框），左右可以换页；再按一次返回才退出应用。
                    // 电视遥控器的返回很容易连按，一下就把 App 退掉会让用户以为页面丢了。
                    tvBackArmedForExit = true
                    Log.i(
                        TAG,
                        "back: 电视上没有上一页，先回工具栏（还有 ${tabs.size} 个标签页，再按返回退出）"
                    )
                    (tabStrip.getChildAt(currentIndex) ?: menuButton).requestFocus()
                    Toast.makeText(this@MainActivity, "再按一次返回退出应用", Toast.LENGTH_SHORT).show()
                } else {
                    // 手机：这一页没有上一页就退出应用，但绝不顺手关标签页——
                    // 用户要的是「返回键退出、页面还在」，关标签只能由他自己按 ✕。
                    // 退出直接 finish()：不要用 isEnabled=false + onBackPressed() 那套——
                    // 任务只是退到后台时 Activity 还活着，回调却一直停用，下次回来返回键会直接退出。
                    Log.i(
                        TAG,
                        "back: 没有上一页，退出且保留 ${tabs.size} 个标签页（bfSize=${bf.size}, index=${bf.currentIndex}, url=${webView.url}）"
                    )
                    finish()
                }
            }
        })
    }

    /** 退出网页全屏：摘掉最上层那个视图，恢复系统栏和网页焦点 */
    private fun hideCustomViewNow() {
        val view = customView ?: return
        (window.decorView as? FrameLayout)?.removeView(view)
        customView = null
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        if (tvMode) tabs.getOrNull(currentIndex)?.webView?.requestFocus()
    }

    /**
     * 在虚拟光标的位置给 WebView 发一个真实的触摸 tap。
     *
     * 不能只靠页面里的合成 click：WebView 不把脚本点击当用户手势，站内跳转会被当成
     * "替换当前历史"，表现就是光标点开的页面一按返回键直接退出、回不到上一页。
     * 真实触摸事件走 Chromium 的手势识别，链接跳转/表单提交/输入框聚焦都和手指点一样。
     */
    private fun tapAtCursor(wv: WebView) {
        wv.evaluateJavascript("window.__wsTvPoint?JSON.stringify(window.__wsTvPoint()):''") { raw ->
            val inner = try {
                org.json.JSONTokener(raw ?: "").nextValue() as? String
            } catch (e: Exception) {
                null
            }
            val obj = try {
                if (inner.isNullOrEmpty()) null else org.json.JSONObject(inner)
            } catch (e: Exception) {
                null
            }
            if (obj == null) {
                // 页面没注入光标脚本：退回页面内合成点击（至少能点，历史问题只在极少数页面出现）
                wv.evaluateJavascript("window.__wsTvClick&&window.__wsTvClick()", null)
                return@evaluateJavascript
            }
            val dprRaw = obj.optDouble("dpr", 1.0).toFloat()
            val dpr = if (dprRaw > 0f) dprRaw else 1f
            val maxX = (wv.width - 2).coerceAtLeast(1).toFloat()
            val maxY = (wv.height - 2).coerceAtLeast(1).toFloat()
            val x = (obj.optDouble("x", 0.0).toFloat() * dpr).coerceIn(1f, maxX)
            val y = (obj.optDouble("y", 0.0).toFloat() * dpr).coerceIn(1f, maxY)
            val downTime = SystemClock.uptimeMillis()
            try {
                val down = MotionEvent.obtain(
                    downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
                )
                wv.dispatchTouchEvent(down)
                down.recycle()
                wv.postDelayed({
                    val up = MotionEvent.obtain(
                        downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0
                    )
                    wv.dispatchTouchEvent(up)
                    up.recycle()
                }, 40)
                // 点到输入框：主动把软键盘叫出来。电视上没有实体键盘，WebView 有时
                // 只把焦点给了 <input> 却不弹输入法（MuMu/部分电视复现），这里补一手。
                // 注意别 wv.requestFocus()：WebView 会顺手把键盘焦点还给页面上第一个
                // 可聚焦元素（真实复现：输入框刚拿到焦点就被挪到按钮上）。
                if (obj.optBoolean("editable", false)) {
                    wv.postDelayed({ showImeForWebView(wv) }, 300)
                }
            } catch (e: Exception) {
                Log.w(TAG, "tv tap 失败: ${e.message}")
            }
        }
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
        hideOpenAppBar()   // 换标签页了，上一条"打开 App"的提示过期
        hideNavMenu()
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
        updateTabStrip()
        hideTabSwitcher()
        persistTabs()
        if (tvMode) {
            tvPageActive = false
            tvBackArmedForExit = false
            bindTvFocusTargets(wv)
            wv.requestFocus()
        }
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
            if (tvMode) {
                tvPageActive = false
                tvBackArmedForExit = false
                bindTvFocusTargets(wv)
                // 在切换器里关掉当前页时别把焦点抢回网页，否则遥控器就困在网页上了
                if (tabSwitcher.visibility != View.VISIBLE) wv.requestFocus()
            }
        }
        updateTabStrip()
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
            // 本机离线播放页（127.0.0.1/p/…）每次进程重启端口和令牌都会变，存下来也是死链
            if (isLocalPlayerPage(raw)) continue
            if (i == currentIndex) savedCurrent = saved.size
            saved.add(TabStore.SavedTab(withoutSharedParam(raw), tab.title, tab.alias ?: "", tab.badge ?: ""))
        }
        if (saved.isNotEmpty() && savedCurrent >= 0) {
            TabStore.save(this, saved, savedCurrent, commit)
        }
    }

    /** ?shared= 是分享时的一次性交接参数（对应的文件登记不跨进程），恢复时带着只会让页面拿到空内容 */
    private fun withoutSharedParam(url: String): String {
        val qIdx = url.indexOf('?')
        if (qIdx < 0) return url
        // 查询串可能落在 # 之前（普通地址 a/b?x=1#f），也可能在 # 之后（hash 路由：
        // /pbc/usercenter/web/#/oauth/authorize?...）。旧写法假设 # 一定在后面，
        // 遇到 hash 在前的地址 substring(起点 > 终点) 直接崩 App，所以按「有 ? 的那一段」取。
        val fragIdx = url.indexOf('#').let { if (it >= 0) it else url.length }
        val from = qIdx + 1
        val to = if (qIdx < fragIdx) fragIdx else url.length
        val kept = url.substring(from, to)
            .split('&')
            .filter { it.substringBefore('=').trim() != "shared" }
        if (kept.isEmpty()) return url.substring(0, qIdx) + url.substring(to)
        return url.substring(0, from) + kept.joinToString("&") + url.substring(to)
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

    /** 圆片上的字：优先用单独设的「页面字」，否则备注名/标题的第一个字（英文取大写） */
    private fun tabInitial(tab: Tab): String {
        tab.badge?.trim()?.takeIf { it.isNotEmpty() }?.let { badge ->
            val c = badge.firstOrNull { !it.isWhitespace() } ?: return "•"
            return if (c.isLetter()) c.uppercaseChar().toString() else c.toString()
        }
        val name = tab.alias?.takeIf { it.isNotBlank() } ?: tab.title
        // 书名号/引号这类开头没有信息量，取第一个「字」或数字
        val c = name.firstOrNull { it.isLetterOrDigit() }
            ?: name.trim().firstOrNull { !it.isWhitespace() }
            ?: return "•"
        return if (c.isLetter()) c.uppercaseChar().toString() else c.toString()
    }

    /**
     * 底栏的标签条：每页一个圆片，当前页紫色。名字/数量变了才重建（网页换页会不停来回调，
     * 重建会打断滑动和遥控器焦点）；只换选中页时走下面那半段轻量更新。
     */
    private fun updateTabStrip() {
        val names = tabs.map {
            (it.alias ?: "") + "\u0002" + (it.badge ?: "") + "\u0002" + it.title
        }
        val sig = names.joinToString("\u0001")
        if (sig != tabStripSignature || tabStrip.childCount != tabs.size) {
            tabStripSignature = sig
            val keepScroll = tabScroll.scrollX
            tabStrip.removeAllViews()
            for (tab in tabs) tabStrip.addView(buildTabChip(tab))
            tabScroll.post {
                tabScroll.scrollTo(keepScroll, 0)
                updateTabScrollButtons()
            }
        }
        val onBg = ContextCompat.getDrawable(this, R.drawable.bg_tab_chip_on)
        val offBg = ContextCompat.getDrawable(this, R.drawable.bg_tab_chip)
        for (i in 0 until tabStrip.childCount) {
            val chip = tabStrip.getChildAt(i) as? TextView ?: continue
            val on = i == currentIndex
            chip.isSelected = on
            chip.background = if (on) onBg else offBg
            chip.setTextColor(if (on) Color.WHITE else Color.parseColor("#5F6368"))
        }
        if (tvMode) {
            val wvId = tabs.getOrNull(currentIndex)?.webView?.id ?: View.NO_ID
            if (wvId != View.NO_ID) {
                for (i in 0 until tabStrip.childCount) {
                    tabStrip.getChildAt(i).nextFocusUpId = wvId
                }
            }
            bindTabStripFocus()
        }
        if (currentIndex != revealedTabIndex) {
            revealedTabIndex = currentIndex
            revealCurrentChip()
        }
    }

    /** 当前页的圆片滚进可视范围（标签多了以后切页不会切到看不见的地方） */
    private fun revealCurrentChip() {
        val chip = tabStrip.getChildAt(currentIndex) ?: return
        tabScroll.post {
            val viewLeft = tabScroll.scrollX
            val viewRight = viewLeft + tabScroll.width
            val target = when {
                chip.left < viewLeft + dp(4) -> chip.left - dp(4)
                chip.right > viewRight - dp(4) -> chip.right - tabScroll.width + dp(4)
                else -> -1
            }
            if (target >= 0) tabScroll.smoothScrollTo(target.coerceAtLeast(0), 0)
            updateTabScrollButtons()
        }
    }

    private fun buildTabChip(tab: Tab): TextView {
        val size = dp(28)
        val chip = TextView(this)
        chip.id = View.generateViewId()
        chip.text = tabInitial(tab)
        chip.gravity = Gravity.CENTER
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        chip.includeFontPadding = false
        chip.isSingleLine = true
        chip.isClickable = true
        chip.isFocusable = tvMode
        chip.contentDescription =
            (tab.alias?.takeIf { it.isNotBlank() } ?: tab.title.ifBlank { "新标签页" })
        val lp = LinearLayout.LayoutParams(size, size)
        lp.marginStart = dp(3)
        lp.marginEnd = dp(3)
        chip.layoutParams = lp
        if (tvMode) {
            chip.setOnFocusChangeListener { _, hasFocus ->
                // 圆片只有 28dp，光靠描边不够醒目：聚焦时放大并抬到相邻圆片上面，
                // 遥控器左右选页时能清楚看到焦点落在哪一片
                chip.animate()
                    .scaleX(if (hasFocus) 1.3f else 1f)
                    .scaleY(if (hasFocus) 1.3f else 1f)
                    .setDuration(90)
                    .start()
                chip.translationZ = if (hasFocus) dp(6).toFloat() else 0f
                if (hasFocus) {
                    // 遥控器走到被遮住的圆片时，把它滚到中间
                    tabScroll.post {
                        val target = chip.left - (tabScroll.width - chip.width) / 2
                        tabScroll.smoothScrollTo(target.coerceAtLeast(0), 0)
                        updateTabScrollButtons()
                    }
                }
            }
        }
        chip.setOnClickListener {
            markOwnUi()
            val index = tabs.indexOf(tab)
            if (index >= 0) switchTo(index)
        }
        // 标签页管理（切换器/关闭/备注名）挪到长按圆片：底栏只留一个「三条线」
        chip.setOnLongClickListener {
            markOwnUi()
            userTouchedSinceStart = true
            val index = tabs.indexOf(tab)
            if (index >= 0) switchTo(index)
            openTabSwitcher()
            true
        }
        if (tvMode) {
            // 标签圆片专用焦点框（深色外圈 + 白内圈），普通按钮的淡紫描边在紫圆片上看不见
            chip.foreground = ContextCompat.getDrawable(this, R.drawable.bg_tv_chip_focus)
            val wvId = tabs.getOrNull(currentIndex)?.webView?.id ?: View.NO_ID
            if (wvId != View.NO_ID) chip.nextFocusUpId = wvId
        }
        return chip
    }

    /** 标签条超出可视宽度时，左右两侧各露一个翻页箭头 */
    private fun updateTabScrollButtons() {
        val canLeft = tabScroll.canScrollHorizontally(-1)
        val canRight = tabScroll.canScrollHorizontally(1)
        tabScrollLeft.visibility = if (canLeft) View.VISIBLE else View.GONE
        tabScrollRight.visibility = if (canRight) View.VISIBLE else View.GONE
        if (tvMode) bindTabStripFocus()
    }

    private fun scrollTabStrip(dir: Int) {
        val amount = (tabScroll.width * 2 / 3).coerceAtLeast(dp(80)) * dir
        tabScroll.smoothScrollBy(amount, 0)
        tabScroll.postDelayed({ updateTabScrollButtons() }, 300)
    }

    private fun openTabSwitcher() {
        hideNavMenu()
        captureThumb(tabs[currentIndex])
        tabAdapter.notifyDataSetChanged()
        switcherFocusPosition = currentIndex
        tabSwitcher.visibility = View.VISIBLE
        if (tvMode) {
            // 切换器是整屏浮层：把底栏和网页的焦点挡掉，遥控器才不会"穿"到
            // 浮层后面把「已下载」按了（真实复现：在切换器里按 ↓ 再 OK 打开了已下载页）
            (findViewById<View>(R.id.bottomBar) as ViewGroup).descendantFocusability =
                ViewGroup.FOCUS_BLOCK_DESCENDANTS
            webView.isFocusable = false
            webView.clearFocus()
            // 电视上给当前卡片一个明确的「编辑 / 关闭」入口（右上角 ✕ 太小，
            // 焦点也很难从整张卡片钻进那个角落）
            val edit = findViewById<TextView>(R.id.switcherEdit)
            val close = findViewById<TextView>(R.id.switcherClose)
            edit.visibility = View.VISIBLE
            close.visibility = View.VISIBLE
            edit.foreground = ContextCompat.getDrawable(this, R.drawable.bg_tv_focus)
            close.foreground = ContextCompat.getDrawable(this, R.drawable.bg_tv_focus)
            findViewById<View>(R.id.btnNewTab).foreground =
                ContextCompat.getDrawable(this, R.drawable.bg_tv_focus)
            edit.nextFocusRightId = R.id.switcherClose
            close.nextFocusLeftId = R.id.switcherEdit
            close.nextFocusRightId = R.id.switcherClose
            edit.setOnClickListener {
                val p = switcherFocusPosition
                if (p in tabs.indices) editTabAlias(p)
            }
            close.setOnClickListener { closeSwitcherFocusedTab() }
            // 让遥控器直接落到当前标签页的卡片标题上；网格还没布局好就等一帧再试
            tabGrid.scrollToPosition(currentIndex)
            tabGrid.postDelayed({
                val vh = tabGrid.findViewHolderForAdapterPosition(currentIndex) as? TabVH
                val target: View = vh?.header ?: vh?.itemView
                    ?: findViewById(R.id.btnNewTab)
                target.requestFocus()
            }, 120)
        } else {
            findViewById<View>(R.id.switcherEdit).visibility = View.GONE
            findViewById<View>(R.id.switcherClose).visibility = View.GONE
        }
    }

    /** 电视切换器「关闭」：关掉当前焦点所在的卡片；关完把焦点挪到下一张 */
    private fun closeSwitcherFocusedTab() {
        val p = switcherFocusPosition
        if (p !in tabs.indices) return
        val last = tabs.size == 1
        closeTab(p)
        if (last) return
        val next = p.coerceAtMost(tabs.lastIndex)
        switcherFocusPosition = next
        tabGrid.postDelayed({
            val vh = tabGrid.findViewHolderForAdapterPosition(next) as? TabVH
            val target: View = vh?.header ?: vh?.itemView
                ?: findViewById(R.id.btnNewTab)
            target.requestFocus()
        }, 150)
    }

    private fun hideTabSwitcher() {
        tabSwitcher.visibility = View.GONE
        if (tvMode) {
            (findViewById<View>(R.id.bottomBar) as ViewGroup).descendantFocusability =
                ViewGroup.FOCUS_AFTER_DESCENDANTS
            webView.isFocusable = true
            tvPageActive = false
            tvBackArmedForExit = false
            tabs.getOrNull(currentIndex)?.webView?.requestFocus()
        }
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
                    if (this@MainActivity.tvMode) {
                        isFocusable = true
                        foreground = ContextCompat.getDrawable(
                            this@MainActivity, R.drawable.bg_tv_focus
                        )
                    }
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
                val copy = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_nav_copy)
                    background = selectableBackgroundBorderless()
                    alpha = 0.55f
                    contentDescription = "复制网址"
                    setOnClickListener {
                        copyToClipboard(addr)
                        Toast.makeText(this@MainActivity, "已复制网址", Toast.LENGTH_SHORT).show()
                    }
                }
                val btnSize = (30 * resources.displayMetrics.density).toInt()
                row.addView(copy, LinearLayout.LayoutParams(btnSize, btnSize))
                row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(del, LinearLayout.LayoutParams(btnSize, btnSize))
                historyBox.addView(row)
            }
        }
        refreshHistory()
        attachTvIme(input)
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
        showTvImeNow(input)
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

    /** 复制到系统剪贴板（新建标签页的历史记录行上那个复制按钮用） */
    private fun copyToClipboard(text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("网址", text))
        } catch (e: Exception) {
            Log.w(TAG, "复制失败: ${e.message}")
        }
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
            // 有备注名就显示备注名（备注名同时是语音快捷键的名字，按用户自己的叫法显示更好认）。
            // 设了「页面字」的，前面用【】把它标出来；没设就只显示标题，括号不要出现。
            val name = tab.alias?.takeIf { it.isNotBlank() }
                ?: tab.title.ifBlank { "新标签页" }
            val badge = tab.badge?.takeIf { it.isNotBlank() }
            holder.title.text = if (badge != null) "【$badge】$name" else name
            if (tab.thumb != null) {
                holder.thumb.setImageBitmap(tab.thumb)
            } else {
                holder.thumb.setImageDrawable(null)
            }
            holder.close.setOnClickListener { closeTab(position) }
            holder.itemView.setOnClickListener { switchTo(position) }
            // 标题栏：点一下和点卡片一样是切换；长按才是起备注名
            // （挂了长按监听会让这个 View 变成可点击，不补上点击监听的话点标题就没反应了）
            holder.header.setOnClickListener { switchTo(position) }
            holder.header.setOnLongClickListener {
                editTabAlias(position)
                true
            }
            if (tvMode) {
                // 电视：整张卡片和关闭按钮都要能被遥控器选中
                holder.itemView.isFocusable = true
                holder.itemView.foreground =
                    ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tv_focus)
                holder.close.isFocusable = true
                holder.close.foreground =
                    ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tv_focus)
                // 记住焦点在哪张卡片上：切换器底部的「编辑 / 关闭」作用于它
                holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) switcherFocusPosition = position
                }
                holder.header.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) switcherFocusPosition = position
                }
                holder.close.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) switcherFocusPosition = position
                }
                // 最后一行的卡片按 ↓ 直接落到操作条，别在网格里打转
                val lastRowStart = ((tabs.size - 1) / 2) * 2
                if (position >= lastRowStart) {
                    holder.header.nextFocusDownId = R.id.switcherEdit
                    holder.close.nextFocusDownId = R.id.switcherEdit
                }
            }
        }
    }

    private class TabVH(view: View) : RecyclerView.ViewHolder(view) {
        val header: View = view.findViewById(R.id.tabHeader)
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
