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
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        clearButton.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
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

    // ---------- 列表 ----------

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        private val items = mutableListOf<DownloadRecord>()
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
                if (rec.status == DownloadRecord.STATUS_DONE) open(rec)
            }
            holder.retry.setOnClickListener { retry(rec) }
            holder.delete.setOnClickListener { askDelete(rec) }
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
        val retry: ImageButton = view.findViewById(R.id.btnRetry)
        val delete: ImageButton = view.findViewById(R.id.btnDelete)
    }
}
