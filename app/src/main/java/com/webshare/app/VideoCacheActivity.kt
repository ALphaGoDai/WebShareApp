package com.webshare.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
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
 * 缓存视频：网页里缓存下来的视频都在这儿。
 * 每条能播放（走 LocalMediaServer，不联网）、存到手机、分享给别的 App、删掉。
 */
class VideoCacheActivity : AppCompatActivity() {

    private lateinit var adapter: Adapter
    private lateinit var emptyView: View
    private lateinit var clearButton: TextView
    private lateinit var totalView: TextView

    private var items: List<VideoCacheStore.Entry> = emptyList()

    /** 电视上「保存到本地」更准确，而且所有可点的地方都要能用遥控器选中 */
    private var tvMode = false

    private val dayFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_videocache)

        tvMode = TvMode.isTv(this)

        emptyView = findViewById(R.id.emptyView)
        clearButton = findViewById(R.id.btnClear)
        totalView = findViewById(R.id.tvTotal)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        clearButton.setOnClickListener { askClearAll() }
        if (tvMode) {
            val ring = ContextCompat.getDrawable(this, R.drawable.bg_tv_focus)
            findViewById<View>(R.id.btnBack).foreground = ring
            clearButton.isFocusable = true
            clearButton.foreground = ContextCompat.getDrawable(this, R.drawable.bg_tv_focus)
            findViewById<TextView>(R.id.tvHint).text =
                "遥控器：OK 在光标处点视频；左右切按钮。网页里出现视频时，长按 OK 切到工具栏，再按「缓存」。"
        }

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = Adapter()
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        VideoCacheStore.prune(this)   // 文件被外面删掉的条目顺手清掉
        reload()
    }

    private fun reload() {
        items = VideoCacheStore.list(this)
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        clearButton.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        totalView.text = if (items.isEmpty()) {
            "缓存到浏览器里，不联网也能看"
        } else {
            val total = items.sumOf { if (it.size > 0) it.size else 0L }
            "${items.size} 个 · 共 ${DownloadService.fmtSize(total)}"
        }
        if (tvMode) {
            // 遥控器进来就有落点：第一条（没有条目就落到返回）
            val list = findViewById<RecyclerView>(R.id.list)
            list.post {
                val holder = list.findViewHolderForAdapterPosition(0)
                val target: View = holder?.itemView ?: findViewById(R.id.btnBack)
                if (!target.hasFocus()) target.requestFocus()
            }
        }
    }

    // ---------- 每条的动作 ----------

    private fun play(entry: VideoCacheStore.Entry) {
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

    /** 存到手机：借用「已下载」那套（走我们的 DNS 通道 + 用户选的保存位置），源就是本机缓存 */
    private fun saveToPhone(entry: VideoCacheStore.Entry) {
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
            mime = mimeOf(file.name),
            treeUri = tree,
            referer = null
        )
        Toast.makeText(this, "已开始保存到手机（进度看通知栏，完成在「已下载」里）", Toast.LENGTH_LONG).show()
    }

    private fun share(entry: VideoCacheStore.Entry) {
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
            type = mimeOf(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, entry.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 别把自己列进分享目标：本浏览器自己也认 video 打头的分享（那是"别的 App 发给浏览器"
            // 的入口），不排掉的话——手机上只有一个能收的应用时——点分享会把自己又打开一遍
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(android.content.ComponentName(this@VideoCacheActivity, MainActivity::class.java))
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

    private fun askDelete(entry: VideoCacheStore.Entry) {
        AlertDialog.Builder(this)
            .setTitle("删掉「${entry.title}」？")
            .setMessage("缓存文件会一起删掉（${DownloadService.fmtSize(entry.size)}），删了要重新缓存。")
            .setPositiveButton("删除") { _, _ ->
                VideoCacheStore.remove(this, entry.id)
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askClearAll() {
        val total = items.sumOf { if (it.size > 0) it.size else 0L }
        AlertDialog.Builder(this)
            .setTitle("清空全部缓存视频？")
            .setMessage("${items.size} 个视频、共 ${DownloadService.fmtSize(total)} 都会删掉。")
            .setPositiveButton("全部清空") { _, _ ->
                VideoCacheStore.clear(this)
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        else -> "video/*"
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val source: TextView = v.findViewById(R.id.source)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_videocache, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            val kindLabel = if (entry.kind == MediaWatch.KIND_HLS) "网页分片合成" else "视频文件"
            holder.title.text = entry.title
            holder.subtitle.text = "$kindLabel · ${DownloadService.fmtSize(entry.size)} · ${dayFmt.format(Date(entry.createdAt))}"
            holder.source.text = entry.pageUrl.ifBlank { entry.url }

            holder.itemView.setOnClickListener { play(entry) }
            holder.itemView.findViewById<View>(R.id.btnPlay).setOnClickListener { play(entry) }
            holder.itemView.findViewById<View>(R.id.btnSave).setOnClickListener { saveToPhone(entry) }
            holder.itemView.findViewById<View>(R.id.btnShare).setOnClickListener { share(entry) }
            holder.itemView.findViewById<View>(R.id.btnDelete).setOnClickListener { askDelete(entry) }

            if (tvMode) {
                val ring = ContextCompat.getDrawable(this@VideoCacheActivity, R.drawable.bg_tv_focus)
                holder.itemView.isFocusable = true
                holder.itemView.foreground = ring
                holder.itemView.findViewById<TextView>(R.id.btnSave).text = "保存到本地"
                for (id in intArrayOf(R.id.btnPlay, R.id.btnSave, R.id.btnShare, R.id.btnDelete)) {
                    val b = holder.itemView.findViewById<TextView>(id)
                    b.isFocusable = true
                    b.foreground = ContextCompat.getDrawable(
                        this@VideoCacheActivity, R.drawable.bg_tv_focus
                    )
                }
            }
        }
    }
}
