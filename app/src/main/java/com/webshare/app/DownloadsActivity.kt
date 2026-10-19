package com.webshare.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已下载：一个页面两个标签——「下载记录」（网页里存到手机的文件）和「缓存视频」
 * （长按底栏「已下载」缓存在浏览器里的视频，可离线播放 / 保存到手机 / 分享 / 删除）。
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var history: DownloadHistory
    private lateinit var adapter: Adapter
    private lateinit var emptyView: View
    private lateinit var clearButton: TextView
    private var downloadDoneReceiver: BroadcastReceiver? = null

    // ---------- 两个标签 ----------
    private lateinit var tabRow: View
    private lateinit var tabDownloads: View
    private lateinit var tabCache: View
    private lateinit var tabDownloadsText: TextView
    private lateinit var tabCacheText: TextView
    private lateinit var tabDownloadsLine: View
    private lateinit var tabCacheLine: View
    private lateinit var cacheList: RecyclerView
    private lateinit var cacheAdapter: CacheAdapter
    private lateinit var cacheEmptyView: View

    private var activeTab = TAB_DOWNLOADS
    private var cacheItems: List<VideoCacheStore.Entry> = emptyList()
    private var cacheTasks: List<VideoCacheProgress.Task> = emptyList()
    private var cacheSignature = ""
    private val cacheDayFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /** 电视上所有可点的地方都要能用遥控器选中 */
    private var tvMode = false

    private val poll = Handler(Looper.getMainLooper())
    private var lastSignature = ""
    private var reloading = false

    // ---------- 批量选择 ----------
    private lateinit var selectionBar: View
    private lateinit var batchBar: View
    private lateinit var selCount: TextView
    private lateinit var selectAllButton: TextView
    private lateinit var batchRetryButton: Button
    private lateinit var batchShareButton: Button
    private lateinit var batchDeleteButton: Button

    /** 选择模式（长按任意一条进入）；选中的是下载记录的 id（DownloadRecord.id 是 Long） */
    private var selectionMode = false
    private val selected = linkedSetOf<Long>()

    /** 当前列表快照：批量操作按它取记录（轮询刷新时更新） */
    private var currentItems: List<DownloadRecord> = emptyList()

    /** 上一次推给列表的选择状态，用来避免每轮轮询都重绑一遍 */
    private var selectionSignature = ""

    private val pollRunner = object : Runnable {
        override fun run() {
            reload()
            reloadCache()   // 缓存完成时列表/标签上的数量不用退出这一页也能更新
            poll.postDelayed(this, 800)
        }
    }

    companion object {
        private const val TAB_DOWNLOADS = 0
        private const val TAB_CACHE = 1

        /** 外部（缓存完成通知）打开这一页时指定先看哪个标签 */
        const val EXTRA_TAB = "tab"
        const val TAB_CACHE_VALUE = "cache"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        history = DownloadHistory(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        clearButton = findViewById(R.id.btnClear)
        clearButton.setOnClickListener {
            if (activeTab == TAB_CACHE) askClearCache() else askClearAll()
        }
        emptyView = findViewById(R.id.emptyView)
        cacheEmptyView = findViewById(R.id.cacheEmptyView)

        tabRow = findViewById(R.id.tabRow)
        tabDownloads = findViewById(R.id.tabDownloads)
        tabCache = findViewById(R.id.tabCache)
        tabDownloadsText = findViewById(R.id.tabDownloadsText)
        tabCacheText = findViewById(R.id.tabCacheText)
        tabDownloadsLine = findViewById(R.id.tabDownloadsLine)
        tabCacheLine = findViewById(R.id.tabCacheLine)
        tabDownloads.setOnClickListener { selectTab(TAB_DOWNLOADS) }
        tabCache.setOnClickListener { selectTab(TAB_CACHE) }

        selectionBar = findViewById(R.id.selectionBar)
        batchBar = findViewById(R.id.batchBar)
        selCount = findViewById(R.id.tvSelCount)
        selectAllButton = findViewById(R.id.btnSelectAll)
        batchRetryButton = findViewById(R.id.btnBatchRetry)
        batchShareButton = findViewById(R.id.btnBatchShare)
        batchDeleteButton = findViewById(R.id.btnBatchDelete)
        findViewById<ImageButton>(R.id.btnSelExit).setOnClickListener { exitSelection() }
        selectAllButton.setOnClickListener { toggleSelectAll() }
        batchRetryButton.setOnClickListener { batchRetry() }
        batchShareButton.setOnClickListener { batchShare() }
        batchDeleteButton.setOnClickListener { batchDelete() }

        adapter = Adapter()
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        cacheAdapter = CacheAdapter()
        cacheList = findViewById(R.id.cacheList)
        cacheList.layoutManager = LinearLayoutManager(this)
        cacheList.adapter = cacheAdapter

        setupTvFocus()
        selectTab(if (intent?.getStringExtra(EXTRA_TAB) == TAB_CACHE_VALUE) TAB_CACHE else TAB_DOWNLOADS)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getStringExtra(EXTRA_TAB) == TAB_CACHE_VALUE) selectTab(TAB_CACHE)
    }

    /** 切标签：两个列表叠在同一块区域，按标签显示其中一个（批量选择只属于下载记录） */
    private fun selectTab(tab: Int) {
        if (tab == TAB_CACHE && selectionMode) exitSelection()
        activeTab = tab
        val downloads = tab == TAB_DOWNLOADS

        val selectedColor = ContextCompat.getColor(this, R.color.purple_500)
        val idleColor = 0xFF5F6368.toInt()
        tabDownloadsText.setTextColor(if (downloads) selectedColor else idleColor)
        tabDownloadsText.setTypeface(null, if (downloads) Typeface.BOLD else Typeface.NORMAL)
        tabDownloadsLine.visibility = if (downloads) View.VISIBLE else View.INVISIBLE
        tabCacheText.setTextColor(if (downloads) idleColor else selectedColor)
        tabCacheText.setTypeface(null, if (downloads) Typeface.NORMAL else Typeface.BOLD)
        tabCacheLine.visibility = if (downloads) View.INVISIBLE else View.VISIBLE

        findViewById<View>(R.id.list).visibility = if (downloads) View.VISIBLE else View.GONE
        cacheList.visibility = if (downloads) View.GONE else View.VISIBLE
        emptyView.visibility =
            if (downloads && currentItems.isEmpty()) View.VISIBLE else View.GONE
        cacheEmptyView.visibility =
            if (!downloads && cacheItems.isEmpty() && cacheTasks.isEmpty()) View.VISIBLE else View.GONE

        if (downloads) updateSelectionUi() else updateHeaderUi()
        if (!downloads) {
            reloadCache()
            if (tvMode) {
                // 遥控器切到缓存标签就有落点：第一条（没有条目就落到返回）。
                // 等一拍再找：刚切过来时列表可能还没量好，拿不到第一条就先把焦点留在返回上。
                cacheList.postDelayed({
                    val holder = cacheList.findViewHolderForAdapterPosition(0)
                    val target: View = holder?.itemView ?: findViewById(R.id.btnBack)
                    if (!target.hasFocus()) target.requestFocus()
                }, 120)
            }
        }
    }

    /** 电视：顶栏、标签和批量操作栏的按钮都要能用遥控器选中，并画出焦点框 */
    private fun setupTvFocus() {
        tvMode = TvMode.isTv(this)
        if (!tvMode) return
        val ring = ContextCompat.getDrawable(this, R.drawable.bg_tv_focus)
        findViewById<View>(R.id.btnBack).foreground = ring
        clearButton.isFocusable = true
        clearButton.foreground = ContextCompat.getDrawable(this, R.drawable.bg_tv_focus)
        selectAllButton.isFocusable = true
        selectAllButton.foreground = ContextCompat.getDrawable(this, R.drawable.bg_tv_focus)
        for (id in intArrayOf(R.id.tabDownloads, R.id.tabCache)) {
            val v = findViewById<View>(id)
            v.isFocusable = true
            v.foreground = ContextCompat.getDrawable(this, R.drawable.bg_tv_focus)
        }
    }

    override fun onResume() {
        super.onResume()
        registerDownloadDoneReceiver()
        reload()
        reloadCache()
        poll.postDelayed(pollRunner, 800)
    }

    override fun onPause() {
        unregisterDownloadDoneReceiver()
        super.onPause()
        poll.removeCallbacks(pollRunner)
    }

    private fun registerDownloadDoneReceiver() {
        if (downloadDoneReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != DownloadService.ACTION_DONE) return
                val uriStr = intent.getStringExtra(DownloadService.EXTRA_SAVED_URI) ?: return
                DownloadOpenFab.show(
                    this@DownloadsActivity,
                    uriStr,
                    intent.getStringExtra(DownloadService.EXTRA_SAVED_NAME),
                    intent.getStringExtra(DownloadService.EXTRA_MIME)
                )
            }
        }
        downloadDoneReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(DownloadService.ACTION_DONE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterDownloadDoneReceiver() {
        downloadDoneReceiver?.let { runCatching { unregisterReceiver(it) } }
        downloadDoneReceiver = null
        DownloadOpenFab.dismiss()
    }

    private fun reload() {
        if (reloading) return
        reloading = true
        Thread {
            val items = history.all()
            runOnUiThread {
                reloading = false
                show(items)
            }
        }.start()
    }

    private fun show(items: List<DownloadRecord>) {
        adapter.submit(items)
        currentItems = items
        // 记录被清掉/消失了，选择里也跟着去掉，别让"已选 N 项"里混着不存在的东西
        val ids = items.map { it.id }.toSet()
        selected.retainAll(ids)
        if (selected.isEmpty() && selectionMode) selectionMode = false
        emptyView.visibility =
            if (activeTab == TAB_DOWNLOADS && items.isEmpty()) View.VISIBLE else View.GONE
        updateSelectionUi()
        val signature = items.joinToString("|") {
            "${it.id}:${it.status}:${it.received}:${it.total}:${it.size}:${it.savedName}"
        }
        if (signature != lastSignature) {
            lastSignature = signature
            adapter.notifyDataSetChanged()
        }
    }

    // ---------- 单条操作 ----------

    private fun open(rec: DownloadRecord) {
        val uriStr = rec.savedUri ?: return
        DownloadUi.openFile(this, uriStr, rec.displayName.ifBlank { rec.name }, rec.mime)
    }

    private fun retry(rec: DownloadRecord) {
        DownloadHistory(this).remove(rec.id)
        DownloadService.start(
            context = this,
            url = rec.url,
            name = rec.name,
            mime = rec.mime,
            treeUri = rec.treeUri,
            referer = rec.referer
        )
        Toast.makeText(this, "已重新开始下载", Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun askDelete(rec: DownloadRecord) {
        if (rec.status != DownloadRecord.STATUS_DONE || rec.savedUri == null) {
            // 没落盘的（进行中/失败）只可能是移除记录
            confirm(
                "移除这条下载记录？",
                "移除"
            ) {
                history.remove(rec.id)
                reload()
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除「${rec.displayName}」？")
            .setPositiveButton("删除文件并移除记录") { _, _ ->
                deleteSavedFile(rec.savedUri)
                history.remove(rec.id)
                reload()
            }
            .setNeutralButton("仅移除记录") { _, _ ->
                history.remove(rec.id)
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askClearAll() {
        AlertDialog.Builder(this)
            .setTitle("清空下载记录？")
            .setPositiveButton("删除全部文件并清空") { _, _ ->
                for (rec in history.all()) deleteSavedFile(rec.savedUri)
                history.clear()
                reload()
            }
            .setNeutralButton("仅清空记录") { _, _ ->
                history.clear()
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSavedFile(uriStr: String?): Boolean {
        if (uriStr.isNullOrEmpty()) return false
        return try {
            val uri = Uri.parse(uriStr)
            if (DocumentsContract.isDocumentUri(this, uri)) {
                DocumentsContract.deleteDocument(contentResolver, uri)
            } else {
                contentResolver.delete(uri, null, null) > 0
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun confirm(title: String, positive: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setPositiveButton(positive) { _, _ -> action() }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 批量选择与批量操作 ----------

    private fun enterSelection(id: Long) {
        selectionMode = true
        selected.clear()
        selected.add(id)
        updateSelectionUi()
    }

    private fun exitSelection() {
        selectionMode = false
        selected.clear()
        updateSelectionUi()
    }

    private fun toggleSelection(id: Long) {
        if (!selected.remove(id)) selected.add(id)
        if (selected.isEmpty()) selectionMode = false
        updateSelectionUi()
    }

    private fun toggleSelectAll() {
        val allPicked = currentItems.isNotEmpty() && currentItems.all { selected.contains(it.id) }
        selected.clear()
        if (!allPicked) currentItems.forEach { selected.add(it.id) }
        if (selected.isEmpty()) selectionMode = false
        updateSelectionUi()
    }

    /** 选择模式的界面状态：顶栏（已选几项/全选）、底栏按钮可用性、清空按钮的显示 */
    private fun updateSelectionUi() {
        val showSelection = selectionMode && activeTab == TAB_DOWNLOADS
        selectionBar.visibility = if (showSelection) View.VISIBLE else View.GONE
        batchBar.visibility = if (showSelection) View.VISIBLE else View.GONE
        // 选择模式下把标签行收起来，专心勾选
        tabRow.visibility = if (showSelection) View.GONE else View.VISIBLE
        updateHeaderUi()
        if (showSelection) {
            val picked = currentItems.filter { selected.contains(it.id) }
            selCount.text = "已选 ${picked.size} 项"
            val allPicked = currentItems.isNotEmpty() && picked.size == currentItems.size
            selectAllButton.text = if (allPicked) "取消全选" else "全选"
            setEnabled(batchRetryButton, picked.any { it.status == DownloadRecord.STATUS_FAILED })
            setEnabled(batchShareButton, picked.any { it.savedUri != null })
            setEnabled(batchDeleteButton, picked.isNotEmpty())
        }
        adapter.selectionMode = selectionMode
        // 800ms 轮询每一轮都会走到这里；选择状态没变就别整体重绑
        val sig = selectionMode.toString() + "|" + selected.joinToString(",")
        if (sig != selectionSignature) {
            selectionSignature = sig
            adapter.notifyDataSetChanged()
        }
    }

    /** 顶栏「清空」跟着当前标签走：下载记录清记录，缓存视频清缓存 */
    private fun updateHeaderUi() {
        clearButton.text = if (activeTab == TAB_CACHE) "清空缓存" else "清空"
        clearButton.visibility = when {
            selectionMode && activeTab == TAB_DOWNLOADS -> View.GONE
            activeTab == TAB_CACHE -> if (cacheItems.isEmpty()) View.GONE else View.VISIBLE
            currentItems.isEmpty() -> View.GONE
            else -> View.VISIBLE
        }
    }

    private fun setEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    /** 批量重试：只对失败的条目生效（进行中/已完成的不动） */
    private fun batchRetry() {
        val picked = currentItems.filter {
            selected.contains(it.id) && it.status == DownloadRecord.STATUS_FAILED
        }
        if (picked.isEmpty()) {
            Toast.makeText(this, "选中的条目里没有下载失败的", Toast.LENGTH_SHORT).show()
            return
        }
        for (rec in picked) {
            history.remove(rec.id)
            DownloadService.start(
                context = this,
                url = rec.url,
                name = rec.name,
                mime = rec.mime,
                treeUri = rec.treeUri,
                referer = rec.referer
            )
        }
        Toast.makeText(this, "已重新开始 ${picked.size} 个下载", Toast.LENGTH_SHORT).show()
        exitSelection()
        reload()
    }

    /** 批量分享：把选中且已存到手机的文件一次性交给系统分享 */
    private fun batchShare() {
        val picked = currentItems.filter { selected.contains(it.id) && it.savedUri != null }
        if (picked.isEmpty()) {
            Toast.makeText(this, "选中的条目里没有已保存的文件", Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList(picked.map { Uri.parse(it.savedUri) })
        val mime = picked.first().mime.ifEmpty { "*/*" }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (picked.all { it.mime == mime }) mime else "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "分享 ${uris.size} 个文件"))
        } catch (e: Exception) {
            Toast.makeText(this, "没有应用能接收这些文件", Toast.LENGTH_SHORT).show()
        }
    }

    /** 批量删除：文件+记录 / 仅记录，两种都先问一次 */
    private fun batchDelete() {
        val picked = currentItems.filter { selected.contains(it.id) }
        if (picked.isEmpty()) return
        val withFile = picked.count { it.savedUri != null }
        AlertDialog.Builder(this)
            .setTitle("删除选中的 ${picked.size} 项？")
            .setMessage(
                if (withFile > 0) "其中 $withFile 个已经存到手机里了（删掉的文件不可恢复）。"
                else "选中的都还没存到手机里，只会移除这些记录。"
            )
            .setPositiveButton("删除文件并移除记录") { _, _ -> deletePicked(picked, true) }
            .setNeutralButton("仅移除记录") { _, _ -> deletePicked(picked, false) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deletePicked(picked: List<DownloadRecord>, deleteFiles: Boolean) {
        var files = 0
        for (rec in picked) {
            if (deleteFiles && rec.savedUri != null && deleteSavedFile(rec.savedUri)) files++
            history.remove(rec.id)
        }
        exitSelection()
        reload()
        Toast.makeText(
            this,
            if (deleteFiles) "已删除 ${picked.size} 项（文件 $files 个）"
            else "已移除 ${picked.size} 条记录",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 选择模式下按返回＝先退出选择，不直接离开这一页 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (selectionMode) {
            exitSelection()
            return
        }
        super.onBackPressed()
    }

    // ---------- 缓存视频（长按底栏「已下载」缓存的视频） ----------

    private fun reloadCache() {
        VideoCacheStore.prune(this)   // 文件被外面删掉的条目顺手清掉
        val items = VideoCacheStore.list(this)
        val tasks = VideoCacheProgress.snapshot()
        val sig = buildString {
            append(items.joinToString("|") { "${it.id}:${it.size}:${it.title}" })
            append("||")
            append(tasks.joinToString("|") {
                "${it.id}:${it.state}:${it.bytes}:${it.total}:${it.percent}:${it.note}:${it.error}"
            })
        }
        if (sig != cacheSignature) {
            cacheSignature = sig
            cacheItems = items
            cacheTasks = tasks
            cacheAdapter.notifyDataSetChanged()
            val total = cacheItems.size + cacheTasks.size
            tabCacheText.text = if (total == 0) "缓存视频" else "缓存视频 $total"
        }
        cacheEmptyView.visibility =
            if (activeTab == TAB_CACHE && cacheItems.isEmpty() && cacheTasks.isEmpty())
                View.VISIBLE else View.GONE
        updateHeaderUi()
    }

    private fun cancelCacheTask(task: VideoCacheProgress.Task) {
        VideoCacheService.cancel(this, task.id)
        VideoCacheProgress.remove(task.id)
        Toast.makeText(this, "已取消缓存", Toast.LENGTH_SHORT).show()
        reloadCache()
    }

    private fun playCache(entry: VideoCacheStore.Entry) {
        val url = LocalMediaServer.playerUrl(this, entry)
        if (url == null) {
            Toast.makeText(this, "本机播放服务起不来，先重启一下 App", Toast.LENGTH_LONG).show()
            return
        }
        // 就在浏览器里打开离线播放页（同一个 App，返回键还能回到这一页）
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW          // 少了它 handleIntent 不会走 webshare:// 那一支
            data = Uri.parse("webshare://play/${entry.id}")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "打不开播放页：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 存到手机：借用「下载记录」那套（走我们的 DNS 通道 + 用户选的保存位置），源就是本机缓存 */
    private fun saveCacheToPhone(entry: VideoCacheStore.Entry) {
        val url = LocalMediaServer.fileUrl(this, entry)
        if (url == null) {
            Toast.makeText(this, "本机播放服务起不来，先重启一下 App", Toast.LENGTH_LONG).show()
            return
        }
        val file = VideoCacheStore.fileFor(this, entry)
        val tree = SaveLocationStore(this).locations().firstOrNull()?.treeUri?.toString()
        // 存出去用干净的名字（缓存文件带 id 前缀，是为了在缓存目录里不重名）；重名系统会自动改名
        val pretty = (entry.title.ifBlank { "视频" }) + "." + file.extension
        DownloadService.start(
            context = this,
            url = url,
            name = pretty,
            mime = cacheMimeOf(file.name),
            treeUri = tree,
            referer = null
        )
        Toast.makeText(this, "已开始保存到手机（进度看通知栏，完成在「下载记录」里）", Toast.LENGTH_LONG).show()
    }

    private fun shareCache(entry: VideoCacheStore.Entry) {
        val file = VideoCacheStore.fileFor(this, entry)
        if (!file.exists()) {
            Toast.makeText(this, "文件不在了", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = cacheMimeOf(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, entry.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 别把自己列进分享目标：本浏览器自己也认 video 打头的分享（那是"别的 App 发给浏览器"
            // 的入口），不排掉的话——手机上只有一个能收的应用时——点分享会把自己又打开一遍
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(this@DownloadsActivity, MainActivity::class.java))
            )
        }
        // 只有自己认识这种分享时，系统那个"只有一个目标就直接打开"的行为会把我们绕回自己身上：
        // 先问清楚有没有别人能收，没有就直说（微信/QQ/相册都算）
        val others = packageManager.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
        if (others.isEmpty()) {
            Toast.makeText(this, "手机上还没有能接收视频的应用（微信、QQ 之类）", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(Intent.createChooser(intent, "分享「${entry.title}」"))
        } catch (e: Exception) {
            Toast.makeText(this, "没有应用能接收这个视频", Toast.LENGTH_SHORT).show()
        }
    }

    private fun askDeleteCache(entry: VideoCacheStore.Entry) {
        AlertDialog.Builder(this)
            .setTitle("删掉「${entry.title}」？")
            .setMessage("缓存文件会一起删掉（${DownloadService.fmtSize(entry.size)}），删了要重新缓存。")
            .setPositiveButton("删除") { _, _ ->
                VideoCacheStore.remove(this, entry.id)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                reloadCache()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askClearCache() {
        val total = cacheItems.sumOf { if (it.size > 0) it.size else 0L }
        AlertDialog.Builder(this)
            .setTitle("清空全部缓存视频？")
            .setMessage("${cacheItems.size} 个视频、共 ${DownloadService.fmtSize(total)} 都会删掉。")
            .setPositiveButton("全部清空") { _, _ ->
                VideoCacheStore.clear(this)
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                reloadCache()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun cacheMimeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "ts" -> "video/mp2t"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            else -> "video/*"
        }

    private inner class CacheAdapter : RecyclerView.Adapter<CacheAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val source: TextView = v.findViewById(R.id.source)
            val progress: ProgressBar = v.findViewById(R.id.cacheProgress)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_videocache, parent, false))

        override fun getItemCount(): Int = cacheTasks.size + cacheItems.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            if (position < cacheTasks.size) {
                bindTask(holder, cacheTasks[position])
            } else {
                bindEntry(holder, cacheItems[position - cacheTasks.size])
            }
        }

        private fun bindTask(holder: VH, task: VideoCacheProgress.Task) {
            holder.title.text = task.title
            holder.source.text = task.pageUrl.ifBlank { "网页视频" }

            val failed = task.state == VideoCacheProgress.STATE_FAILED
            holder.subtitle.setTextColor(if (failed) 0xFFD32F2F.toInt() else 0xFF5F6368.toInt())
            holder.subtitle.text = when {
                failed -> "缓存失败 · ${task.error.ifBlank { "未知错误" }}"
                task.state == VideoCacheProgress.STATE_QUEUED -> "排队中…"
                task.percent >= 0 -> {
                    val size = buildString {
                        append(DownloadService.fmtSize(task.bytes))
                        if (task.total > 0) append(" / ").append(DownloadService.fmtSize(task.total))
                    }
                    "正在缓存 · $size（${task.percent}%）${if (task.note.isNotEmpty()) " · ${task.note}" else ""}"
                }
                else -> "正在缓存 · ${DownloadService.fmtSize(task.bytes)}" +
                    (if (task.note.isNotEmpty()) " · ${task.note}" else "")
            }

            holder.progress.visibility =
                if (failed || task.state == VideoCacheProgress.STATE_QUEUED) View.GONE else View.VISIBLE
            if (task.state == VideoCacheProgress.STATE_QUEUED) {
                holder.progress.visibility = View.GONE
            } else if (task.percent >= 0) {
                holder.progress.isIndeterminate = false
                holder.progress.progress = task.percent.coerceIn(0, 100)
            } else {
                holder.progress.isIndeterminate = true
            }

            // 进行中的条目只有「取消」，失败的条目只有「移除」；播放/保存/分享都没有
            for (id in intArrayOf(R.id.btnPlay, R.id.btnSave, R.id.btnShare)) {
                holder.itemView.findViewById<View>(id).visibility = View.GONE
            }
            val delete = holder.itemView.findViewById<TextView>(R.id.btnDelete)
            delete.visibility = View.VISIBLE
            delete.text = if (failed) "移除" else "取消"
            delete.setTextColor(if (failed) 0xFF5F6368.toInt() else 0xFFD32F2F.toInt())
            delete.setOnClickListener {
                if (failed) {
                    VideoCacheProgress.remove(task.id)
                    reloadCache()
                } else {
                    cancelCacheTask(task)
                }
            }
            holder.itemView.setOnClickListener { }

            if (tvMode) {
                val ring = ContextCompat.getDrawable(this@DownloadsActivity, R.drawable.bg_tv_focus)
                holder.itemView.isFocusable = true
                holder.itemView.foreground = ring
                delete.isFocusable = true
                delete.foreground = ring
            }
        }

        private fun bindEntry(holder: VH, entry: VideoCacheStore.Entry) {
            holder.progress.visibility = View.GONE
            val kindLabel = if (entry.kind == MediaWatch.KIND_HLS) "网页分片合成" else "视频文件"
            holder.title.text = entry.title
            holder.subtitle.setTextColor(0xFF5F6368.toInt())
            holder.subtitle.text =
                "$kindLabel · ${DownloadService.fmtSize(entry.size)} · ${cacheDayFmt.format(Date(entry.createdAt))}"
            holder.source.text = entry.pageUrl.ifBlank { entry.url }

            for (id in intArrayOf(R.id.btnPlay, R.id.btnSave, R.id.btnShare, R.id.btnDelete)) {
                holder.itemView.findViewById<View>(id).visibility = View.VISIBLE
            }
            val delete = holder.itemView.findViewById<TextView>(R.id.btnDelete)
            delete.text = "删除"
            delete.setTextColor(0xFFD32F2F.toInt())

            holder.itemView.setOnClickListener { playCache(entry) }
            holder.itemView.findViewById<View>(R.id.btnPlay).setOnClickListener { playCache(entry) }
            holder.itemView.findViewById<View>(R.id.btnSave).setOnClickListener { saveCacheToPhone(entry) }
            holder.itemView.findViewById<View>(R.id.btnShare).setOnClickListener { shareCache(entry) }
            delete.setOnClickListener { askDeleteCache(entry) }

            if (tvMode) {
                val ring = ContextCompat.getDrawable(this@DownloadsActivity, R.drawable.bg_tv_focus)
                holder.itemView.isFocusable = true
                holder.itemView.foreground = ring
                holder.itemView.findViewById<TextView>(R.id.btnSave).text = "保存到本地"
                for (id in intArrayOf(R.id.btnPlay, R.id.btnSave, R.id.btnShare, R.id.btnDelete)) {
                    val b = holder.itemView.findViewById<TextView>(id)
                    b.isFocusable = true
                    b.foreground = ContextCompat.getDrawable(
                        this@DownloadsActivity, R.drawable.bg_tv_focus
                    )
                }
            }
        }
    }

    // ---------- 列表 ----------

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        private val items = mutableListOf<DownloadRecord>()

        /** 是否处于批量选择模式（决定显示勾选框、藏掉单条按钮） */
        var selectionMode = false
        private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun submit(newItems: List<DownloadRecord>) {
            items.clear()
            items.addAll(newItems)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download, parent, false)
            return VH(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val rec = items[position]
            holder.icon.setImageResource(iconFor(rec))
            holder.title.text = rec.displayName

            val picked = selectionMode && selected.contains(rec.id)
            holder.check.visibility = if (selectionMode) View.VISIBLE else View.GONE
            holder.check.alpha = if (picked) 1f else 0.3f

            val date = dateFmt.format(Date(rec.startedAt))
            when (rec.status) {
                DownloadRecord.STATUS_RUNNING -> {
                    holder.subtitle.setTextColor(0xFF5F6368.toInt())
                    holder.subtitle.text = if (rec.total > 0) {
                        val pct = if (rec.total > 0) (rec.received * 100 / rec.total) else 0
                        "正在下载 · ${DownloadService.fmtSize(rec.received)} / ${DownloadService.fmtSize(rec.total)}（$pct%）"
                    } else {
                        "正在下载 · ${DownloadService.fmtSize(rec.received)}"
                    }
                    holder.progress.visibility = View.VISIBLE
                    if (rec.total > 0) {
                        holder.progress.isIndeterminate = false
                        holder.progress.progress =
                            ((rec.received * 100) / rec.total).toInt().coerceIn(0, 100)
                    } else {
                        holder.progress.isIndeterminate = true
                    }
                    holder.retry.visibility = View.GONE
                }
                DownloadRecord.STATUS_FAILED -> {
                    holder.subtitle.setTextColor(0xFFD32F2F.toInt())
                    holder.subtitle.text = "下载失败 · ${rec.error ?: "未知错误"} · $date"
                    holder.progress.visibility = View.GONE
                    holder.retry.visibility = View.VISIBLE
                }
                else -> {
                    holder.subtitle.setTextColor(0xFF5F6368.toInt())
                    val parts = mutableListOf(
                        DownloadService.fmtSize(rec.displaySize),
                        date,
                        rec.location.ifEmpty { DownloadRecord.locationLabel(rec.treeUri) }
                    )
                    rec.repairNote?.let { parts.add(it) }
                    holder.subtitle.text = parts.joinToString(" · ")
                    holder.progress.visibility = View.GONE
                    holder.retry.visibility = View.GONE
                }
            }

            holder.itemView.setOnClickListener {
                when {
                    selectionMode -> toggleSelection(rec.id)
                    rec.status == DownloadRecord.STATUS_DONE -> open(rec)
                }
            }
            holder.itemView.setOnLongClickListener {
                if (selectionMode) toggleSelection(rec.id) else enterSelection(rec.id)
                true
            }
            holder.retry.setOnClickListener { retry(rec) }
            holder.delete.setOnClickListener { askDelete(rec) }
            // 选择模式下把单条操作收起来，免得一边勾选一边误删
            if (selectionMode) {
                holder.retry.visibility = View.GONE
                holder.delete.visibility = View.GONE
            } else {
                holder.delete.visibility = View.VISIBLE
            }
            if (tvMode) {
                val ring = ContextCompat.getDrawable(
                    this@DownloadsActivity, R.drawable.bg_tv_focus
                )
                holder.itemView.isFocusable = true
                holder.itemView.foreground = ring
                holder.retry.foreground = ring
                holder.delete.foreground = ring
            }
        }

        private fun iconFor(rec: DownloadRecord): Int {
            val mime = rec.mime.lowercase(Locale.ROOT)
            val ext = rec.displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when {
                mime.startsWith("video/") ||
                    ext in setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "ts", "flv") ->
                    R.drawable.ic_file_video
                mime.startsWith("audio/") ||
                    ext in setOf("mp3", "m4a", "wav", "aac", "flac", "ogg") ->
                    R.drawable.ic_file_audio
                mime.startsWith("image/") ||
                    ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") ->
                    R.drawable.ic_file_image
                else -> R.drawable.ic_file_generic
            }
        }
    }

    private class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        val progress: ProgressBar = view.findViewById(R.id.progress)
        val check: ImageView = view.findViewById(R.id.checkMark)
        val retry: ImageButton = view.findViewById(R.id.btnRetry)
        val delete: ImageButton = view.findViewById(R.id.btnDelete)
    }
}
