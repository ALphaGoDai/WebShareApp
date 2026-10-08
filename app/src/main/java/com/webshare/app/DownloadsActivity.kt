package com.webshare.app

import android.content.Intent
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 已下载：浏览器风格的下载记录页。
 * 进行中的显示实时进度（800ms 轮询历史存储），完成的点一下打开，
 * 失败的可以重试；每条都能删除（连文件一起删或只删记录）。
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var history: DownloadHistory
    private lateinit var adapter: Adapter
    private lateinit var emptyView: View
    private lateinit var clearButton: TextView

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
            poll.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        history = DownloadHistory(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        clearButton = findViewById(R.id.btnClear)
        clearButton.setOnClickListener { askClearAll() }
        emptyView = findViewById(R.id.emptyView)

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
    }

    override fun onResume() {
        super.onResume()
        reload()
        poll.postDelayed(pollRunner, 800)
    }

    override fun onPause() {
        super.onPause()
        poll.removeCallbacks(pollRunner)
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
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
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
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uriStr), rec.mime.ifEmpty { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "没有应用能打开这种文件", Toast.LENGTH_SHORT).show()
        }
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
        selectionBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        batchBar.visibility = if (selectionMode) View.VISIBLE else View.GONE
        clearButton.visibility =
            if (selectionMode || currentItems.isEmpty()) View.GONE else View.VISIBLE
        if (selectionMode) {
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
