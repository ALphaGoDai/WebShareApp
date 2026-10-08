# 网页分享助手 (WebShareApp)

一个 Android 应用，用于挂载指定网页并接收系统分享内容。专为华为手机侧边快捷栏（智慧多窗）设计，可将分享的网址/图片传递给挂载的网页进行处理。

## 功能特性

- **WebView 网页挂载**：在应用内加载指定网页
- **接收分享内容**：接收来自其他应用分享的文本（网址）、图片和**视频 / 音频**；分享进来的文件自动送进页面的上传入口（页面没有上传入口时再手动点）
- **设置界面**：可配置打开的网页地址
- **多标签页**（v1.0.27 起）：底部「标签页」按钮打开浏览器式卡片切换器（带页面缩略图），可新建 / 切换 / 关闭标签页，最多 6 个，各标签页独立保持页面状态
- **下载到手机**：网页里的下载按钮 → 确认框选目录 → 走 App 自己的 DNS/TLS 通道下载，带进度通知
- **剪贴板可用**：`http://` 源下网页的「复制 / 粘贴」按钮也能正常工作（桥接 App 剪贴板）
- **文件上传**：网页 `<input type="file">` 可用，带文件的表单由 App 流式组装上传
- **新窗口链接交给系统浏览器**：`window.open` / `target="_blank"` 不再顶掉挂载的页面
- **网页视频能播就读得动**：把设备真实的解码能力（有没有 H.265 硬解）如实告诉网页，并把时间轴损坏的录像在播放前自动修好
- **语音朗读与录音**（v1.0.31 起）：网页的 `speechSynthesis` 朗读由 App 的系统 TTS 顶上（Android WebView 自己不带），`getUserMedia` 录音（学习站点的语音跟读）在 https 页面放行麦克风
- **安全 DNS (DoH)**：支持 DNS-over-HTTPS，可自定义 DoH 服务器，防止 DNS 劫持
- **记住网站登录**（v1.0.39 起）：登录过的站点（政务/统一认证这类跨域名跳转的也算）关掉 App、
  被系统清后台都不用重新登录；Cookie 只在设置页点「清除所有网站登录状态」时才清
- **语音快捷指令**（v1.0.39 起）：设置里给网址起个名字（如「乘车码」），长按桌面图标能直接点进去；
  手机助手（小艺 / 华为「快捷指令」）唤起 App 时也能直达指定那一页
- **JavaScript 接口**：网页可通过 JavaScript 获取分享内容、剪贴板、网络桥接

## 项目结构

```
WebShareApp/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/webshare/app/
│       │   ├── MainActivity.kt          # 主界面：WebView + 分享接收 + 下载拦截 / 文件选择
│       │   ├── SettingsActivity.kt       # 设置界面
│       │   ├── SettingsManager.kt        # 设置管理
│       │   ├── DohDnsResolver.kt         # DoH DNS 解析器
│       │   ├── DohWebViewClient.kt       # 支持 DoH 的 WebView 客户端
│       │   ├── HttpEngine.kt             # 流式 HTTP（DoH + TLS，下载/上传用）
│       │   ├── DownloadService.kt        # 下载前台服务（MediaStore / SAF 目录 + 进度通知）
│       │   ├── DownloadUi.kt             # 下载文件名解析 + 保存目录确认框
│       │   ├── SaveLocationStore.kt      # 记住用户选过的保存目录
│       │   ├── MediaCaps.kt              # 真实解码能力探测（MediaCodecList，供网页查询）
│       │   ├── MediaRepair.kt            # 录像时间轴修复（丢坏帧 + MediaMuxer 重封装 + 结果缓存）
│       │   └── WebAppInterface.kt        # JavaScript 接口（含剪贴板 / 上传 / 外部浏览器）
│       └── res/
│           ├── layout/                   # 布局文件
│           ├── values/                   # 字符串、颜色、主题
│           ├── xml/                      # 网络安全配置等
│           └── drawable/                 # 图标资源
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/
    └── gradle-wrapper.properties
```

## 如何构建

### 方式一：GitHub Actions 在线构建（推荐，无需本地环境）

1. 推送代码到 GitHub，Actions 自动构建
2. 直接下载 Release APK（固定签名，可覆盖安装无需卸载）：

   ```
   https://github.com/ALphaGoDai/WebShareApp/releases/latest/download/WebShareApp.apk
   ```

   手机浏览器打开此链接即可直接下载安装。也可在 App 设置中点击"下载最新版本"。

### 方式二：使用 Android Studio

1. 打开 Android Studio
2. 选择 `File > Open`，选择 `WebShareApp` 目录
3. 等待 Gradle 同步完成（Android Studio 会自动生成 gradle-wrapper.jar）
4. 点击 `Run` 按钮编译并安装到手机

### 方式三：使用命令行

1. 安装 Android SDK（设置 `ANDROID_HOME` 环境变量）
2. 创建 `local.properties` 文件：
   ```
   sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk
   ```
3. 运行 Gradle Wrapper（如果缺少 gradle-wrapper.jar，先安装 Gradle 8.0 并运行 `gradle wrapper`）
4. 执行构建：
   ```
   ./gradlew assembleRelease
   ```
5. APK 输出路径：`app/build/outputs/apk/release/app-release.apk`

> 注意：构建依赖仓库根目录的 `release.keystore` 签名文件（CI 首次构建会自动生成并提交）。所有构建使用同一签名，因此可以直接覆盖安装，无需卸载旧版本，应用设置也会保留。

## 使用方法

### 1. 首次使用

1. 打开应用即加载默认网页（站点地址内置于 App；v1.0.29 起设置页不再有「网页地址」一项，
   地址都在「新建标签页」的地址栏里输）
2. 想去别的地址：底部「标签页」→「＋ 新建标签页」→ 输入网址 → 「访问」
3. 设置页里剩下的都是安全 DNS (DoH) 与应用更新相关选项

### 2. 配置分享内容传递

分享内容有两种传递方式：

**方式 A：URL 占位符（需自行改 App 内置地址，v1.0.29 起设置页没有网址项）**

在设置中将 URL 配置为包含 `{shared}` 占位符，例如：
```
https://your-website.com/process?url={shared}
```
分享内容会替换 `{shared}`（URL 编码后）。

**方式 B：自动附加参数**

如果 URL 不包含 `{shared}`，分享内容会自动以查询参数附加：
```
https://your-website.com?shared=<url_encoded_content>
```

**方式 C：JavaScript 接口**

网页加载后可通过 JavaScript 获取分享内容：

```javascript
// 方式1：回调函数（页面加载完成后自动调用）
window.onSharedContent = function(data) {
    console.log('收到分享内容:', data.type, data.content);
    // data.type: "text" 或 "image"
    // data.content: 分享的文本或图片URI
};

// 方式2：主动调用
var sharedText = Android.getSharedText();   // 获取分享文本
var sharedType = Android.getSharedType();    // 获取分享类型
var settings = Android.getSettings();        // 获取应用设置(JSON)
```

**方式 D：Android.httpPost / Android.httpGet 网络桥接**

由于部分网络环境下系统 DNS 被劫持，WebView 原生 POST/fetch 请求会失败。网页可改用 App 提供的网络桥接（自动走 App 的自定义 DNS + TLS，且兼容 reason phrase 缺失的服务器）：

```javascript
// POST（异步，回调收到结果）
Android.httpPost(
    "https://send.nbhonghong.top:7777/api",     // 请求地址
    "url=https://example.com&foo=bar",           // 请求体
    "application/x-www-form-urlencoded",         // Content-Type（JSON 则传 "application/json"）
    "onPostDone"                                  // 回调函数名（全局函数，可传 "" 用默认 window.onHttpPostResult）
);

function onPostDone(r) {
    // r = { ok: true, status: 200, data: "响应体文本", error: null }
    if (r.ok) {
        console.log('HTTP', r.status, r.data);
    } else {
        console.error('失败:', r.error);
    }
}

// GET（异步，回调格式相同）
Android.httpGet("https://send.nbhonghong.top:7777/api?url=xxx", "onGetDone");

// 带文件的表单上传（文件必须来自网页 <input type="file">，App 按「文件名+大小」
// 找回系统文件选择器授权过的真实文件，流式读取，不经过 base64）
Android.httpPostForm(
    "https://send.nbhonghong.top:7777/api/upload",
    JSON.stringify({
        headers: { "X-Device-Id": "xxx" },
        fields:  [{ name: "cat", value: "待分类" }],
        files:   [{ name: "files", filename: "a.jpg", size: 123456 }]
    }),
    "onUploadDone"     // 回调格式同 httpPost，只是 data 是服务端响应文本
);

// 剪贴板 / 外部浏览器（注入脚本已自动接管 navigator.clipboard 与 window.open，
// 网页一般不需要直接调用）
Android.copyText("要复制的文本");           // 返回是否成功
Android.readClipboard();                    // 返回剪贴板文本（需 App 在前台）
Android.openExternal("https://example.com"); // 用系统浏览器打开

// 真实解码能力（注入脚本已用来自动修正 canPlayType / MediaSource.isTypeSupported，
// 网页若想自己判断可以读这个）
JSON.parse(Android.mediaCaps());
// → { "hevc": true, "hwHevc": false, "sdk": 35 }
//   hevc    : 这台设备能不能解 H.265（软解也算）
//   hwHevc  : 是否是硬件解码
//   sdk     : Build.VERSION.SDK_INT
```

> 说明：桥接请求全部走 `shouldInterceptRequest` 同一套 DNS/TLS 逻辑（DoH 优先、明文被拒自动切 TLS），因此和主页面加载行为一致。回调在主线程执行，可安全操作 DOM。

**方式 E：自动接管 fetch / XMLHttpRequest（推荐，网页零修改）**

v1.0.11 起，App 在页面加载时自动注入接管脚本，网页无需任何修改：

- 页面里的 `fetch` / `XMLHttpRequest`（含 jQuery `$.ajax`）先走 WebView 原生请求
- 原生请求网络级失败时（典型场景：系统 DNS 被劫持到 127.0.0.1），自动改走 App 内部 DoH + TLS 通道重发
- 返回值/事件与原生一致（Promise、`onload`、`onerror`、`responseText` 等），对网页代码完全透明

已知限制：
- WebSocket、原生表单提交（`<form action>` 跳转）不在接管范围
- 请求体支持：字符串、`URLSearchParams`、`FormData`（含文件，走 `httpPostForm` 桥接）、Blob 文本

### 方式 F：下载网页资源到手机（v1.0.24 起）

网页里的下载按钮（`<a download>` 或服务器返回 `Content-Disposition: attachment`）会调用到 App：

1. 弹出确认框显示文件名与大小，让用户选择保存位置：
   - **系统下载目录 Download/**（默认，走 MediaStore，无需任何权限）
   - 之前选过的目录（SAF 授权持久化，跨重启有效）
   - **＋ 选择其他目录…** 打开系统目录选择器（可在里面新建文件夹）
   - 注意：Android 11+ 不允许把「下载」根目录直接授权给应用，需要选它的子目录
2. 确认后由前台服务下载，**走 App 自己的 DoH + TLS 通道**（系统 DownloadManager 用的是被劫持的系统 DNS，且带不上网页登录 Cookie）
3. 通知栏显示进度，完成后可点通知直接打开文件；同一目录重名会自动改名

### 方式 G：剪贴板（复制 / 粘贴）

App 内网页常见是 `http://` 源（非安全上下文），此时 `navigator.clipboard` 整个不存在、
`document.execCommand('paste')` 也被禁用 —— 页面上的「复制」按钮点了毫无反应。

v1.0.24 起注入脚本会把 `navigator.clipboard`（`writeText` / `readText`）接到 App 的
`ClipboardManager` 上：优先用浏览器原生实现，失败或不存在时自动走 App 剪贴板，
网页代码无需改动。

### 方式 H：文件上传（`<input type="file">`）

1. 网页点「上传」→ 系统文件选择器（`onShowFileChooser`），可多选
2. 选中后网页的 `FormData` 带文件提交：原生请求失败时，注入脚本把「字段 + 文件名 + 大小」
   交给 App，App 用系统文件选择器授权过的真实文件**流式**组装 multipart 上传
   （文件内容不经过 JS，也不走 base64；`Content-Length` 按文件流实测，避免被服务器判为协议错误）
3. 同样自动在两个协议（http/https）间回退，兼容 http 源 + TLS 端口的站点

### 方式 I：新窗口链接（`window.open` / `target="_blank"`）

`window.open()` 与 `target="_blank"` 的链接（例如页面里的「浏览器打开」「打开原页面」）
由系统浏览器打开，App 窗口停留在当前页，不会把挂载的网页顶掉。

### 方式 J：网页视频能播就读得动（v1.0.18 起）

站点里播放监控 / 门锁录像（「有人经过」「按门铃」这类标签）时，安卓 WebView 常报
`MEDIA_ERR_DECODE`，页面上就是「加载失败，正在重试…」然后「解码失败」。这不是网页的锅，
录像本身和交付方式各有毛病：

1. **MP4 时间轴塌陷**：录到末尾时一批帧的时间戳被压成 62～125 微秒（正常应该是 30 毫秒级），
   而这批帧的数据同时是错位的（HEVC NAL 单元没对齐、AAC 帧解不出来）。安卓的音频解码器
   碰到第一个这种包就直接躺平，`<video>` 于是报解码错误。
2. **能力上报不实**：网页用 `canPlayType('video/mp4; codecs="hev1"')` 判断能不能播 H.265，
   回答来自 WebView 的 Chrome 内核而不是设备本身——有的 ROM / 旧 WebView 会对 H.265 一律
   乐观作答，网页于是挑了一个本机其实解不了的码流，播到一半就是「解码失败」；
   B 站这类走 MSE 的播放器看的是 `MediaSource.isTypeSupported`，同样的问题。
3. **Range / 206 分段交付**：站点对本地文件按 `Range` 回 206，WebView 的媒体管线吃一串
   被截断、反复重取的 206，会在第一个 GOP 之后报解码错误——同一份文件整份发过去就能播完。

App 的三种处理：

- **如实上报能力**：注入脚本启动时调 `Android.mediaCaps()`（读 `MediaCodecList`），
  设备有 H.265 解码器时继续沿用内核的判断（VP9 / AV1 有内核软解，绝不能降级），
  只有设备确实没有 H.265 解码器、内核却声称能播时，才把 `canPlayType` /
  `isTypeSupported` 里 hevc/h265 的答案改成「不支持」——让网页老老实实走服务端转码。
- **播放前修好录像**：拦截到视频响应时（`video/*` 或 .mp4/.mov/.mkv 等后缀）先扫一遍采样表，
  发现塌陷就把尾部那段坏帧丢掉、原地改采样表（不重编码、不动一字节采样数据），修好的结果按
  URL 缓存在应用缓存目录，同一个视频只修一次。健康视频一帧不动，只多一次扫描。
- **整份交付视频**：从第 0 字节开始的视频请求，一律把整个文件取回来以 200 一次交出
  （去掉 `Content-Range` / `Content-Length` / `Accept-Ranges`），绕开 206 分段这条踩过坑的路径。

「下载到手机」走的是同一套修复：存到本地 / NAS 上的也是修好的文件，通知栏会写明
「已修复录像尾部损坏帧（丢弃 N 个损坏音频帧）」。超过 32 MB 的文件不修（采样表要整包进内存，
太大就跳过，交给服务端转码兜底）；超过 64 MB 的视频仍按原来的 Range 方式交付。

### 方式 K：把手机里的文件分享给站点（v1.0.20 起；v1.0.21 起自动上传）

相册 / 文件管理器里选「分享」时，系统分享面板里会出现本应用（`ACTION_SEND` 与
`SEND_MULTIPLE`，类型覆盖 `image/*`、`video/*`、`audio/*`，和站点 `<input accept>` 一致）。
分享进来之后**不用再点任何按钮**：

1. App 记下文件的 `content://` 地址、登记成「刚选过的文件」，带在网址后面打开站点
   （`?shared=...`，走的就是第 2 步配的那套分享内容传递）；
2. 页面一加载完，App 把文件挂到**同源虚拟地址**上，注入脚本取回来包成 `File`，塞进页面的
   `<input type="file">` 再触发 `change` —— 站点自己的上传处理器（例如 send 站点的
   `uploadPicked`）照常跑：存到「待分类」、进度提示、失败处理都不变；
3. 页面里没有上传入口、或文件取不回来时，App 提示一句，点页面上的上传入口照旧能传
   （那条路打开文件选择器时 App 会把分享的文件直接回填，不用再翻一遍相册）。

网页端自己做不了这一步：网页读不到 `content://`，也没法在没有用户手势时打开文件选择器，
所以「分享完自动保存」只能由 App 把文件送进页面的输入框。文件全程流式读取，不复制、不改名、
不进内存；读相册文件要权限——Android 13+ 是「照片和视频」（`READ_MEDIA_IMAGES/VIDEO/AUDIO`），
更早版本是存储权限，第一次分享时申请一次。

### 方式 L：语音朗读与录音（v1.0.31 起）

Android 的 WebView 不实现 Web Speech API（`window.speechSynthesis` 整个不存在），学习类站点
（轰轰爱学习等）的朗读只能弹「浏览器不支持语音合成」；网页录音用的 `getUserMedia` 又只在
**安全上下文**（https）里才暴露，站点里的语音跟读一直点不动。两件事现在都由 App 补齐：

**① 语音合成（朗读）**——注入脚本用 App 的系统 TTS 补齐 `speechSynthesis` /
`SpeechSynthesisUtterance`，语义照浏览器来：

- `speak()` / `cancel()` 转给系统 TTS，播完/出错回调页面的 `onend` / `onerror`；
  「一句念完接下一句」的链式朗读、`cancel()` 之后旧句不再回调，都按浏览器行为实现；
- `getVoices()` 列出系统 TTS 引擎的嗓音（普通话统一标成 `zh-CN`，页面按 `zh` 挑嗓音挑得中）；
  引擎刚就绪时补发一次 `voiceschanged`（不会和 `getVoices()` 转圈）；
- 空 utterance（手机端「解锁 TTS」的常见写法）当场收尾，不占队列；
- 系统 TTS 没有中文数据时回 `error`，页面可以走自己的降级（预录音 MP3 等），而不是静默无声；
  这时 App 还会弹一次提示（「朗读失败：…… 请到 设置 → 无障碍 → 文字转语音 里检查」），
  告诉用户该去哪儿装引擎或中文语音，而不是让人对着没声音的按钮猜。

清单里那条 `<queries><intent><action android:name="android.intent.action.TTS_SERVICE" /></intent></queries>`
不能删：Android 11 起（targetSdk ≥ 30）有包可见性限制，不声明就看不见任何 TTS 引擎，
`TextToSpeech` 初始化直接返回 ERROR、`getVoices()` 为空、所有朗读静默失败，而网页里的 MP3 播放
完全不受影响——症状就是「组词、造句没声音，点汉字本身有声音」。排查用 `adb logcat -s WebShareApp`
看 `TTS init status=...`（`status=0` 才算拿到引擎）。

朗读用的是手机的系统 TTS 引擎（设置 → 无障碍 / 语言和输入 → 文字转语音输出），要换嗓音在那里装/选。

**② 录音（语音跟读）**——WebView 侧放行麦克风：`onPermissionRequest` + `RECORD_AUDIO` 运行时权限，
第一次录音时系统弹「仅在使用该应用时允许」。但 Chromium 只在安全上下文里把麦克风交给网页，
**`http://` 局域网地址下网页拿不到麦克风**（页面一般提示「不支持录音功能」），要用 https 地址打开。
以轰轰爱学习为例：`http://192.168.31.76:8010` 只能朗读、不能跟读；
`https://hanzi.nbhonghong.top:7777`（Lucky 反代 + 泛域名证书）朗读、跟读都能用。

### 3. 配置安全 DNS (DoH)

1. 在设置页面打开"使用安全 DNS"开关
2. 输入 DoH 服务器地址（或点击常用服务器快速填充）
3. 保存设置

内置常用 DoH 服务器：
- Google: `https://dns.google/dns-query`
- Cloudflare: `https://cloudflare-dns.com/dns-query`
- 阿里 DNS: `https://dns.alidns.com/dns-query`
- DNSPod: `https://doh.pub/dns-query`

### 4. 添加到华为侧边快捷栏

1. 打开华为手机的侧边快捷栏（智慧多窗）
2. 点击编辑/添加应用
3. 将"网页分享助手"添加到快捷栏
4. 在浏览器或其他应用中，选中网址或图片 → 分享 → 选择"网页分享助手"

### 5. 多标签页（v1.0.27 起）

底部导航栏从左到右是 **设置 / 已下载 / 标签页 / 刷新**：

1. 点「标签页」（图标位置是一个紫色方格，方格里的数字就是当前标签页数量，v1.0.38 起；
   在这之前是「标签页图标 + 右上角数字角标」）打开切换器——卡片式列表，
   每张卡片显示页面标题和缩略图；点卡片切换过去，点卡片右上角的 ✕ 关掉它。
   切换是「换页」不是「重开」，别的标签页里填了一半的内容、播放位置都还在。
2. 切换器底部的「＋ 新建标签页」弹出地址栏，默认填好默认网址；改成别的地址后点
   最右边的「访问」就在新标签页打开（地址没写 `http://` 会自动补上）。
   地址栏下方列出**最近访问过的 5 个地址**（页面加载完成时记录，最新在前；存最近 20 条）：
   点一条直接在新标签页打开，右侧 ✕ 把它从历史里删掉，删完列表自动隐藏。
3. 最多 6 个标签页，满了会提示；关掉最后一个标签页等于退出应用；在切换器界面按返回键
   是关切换器，不会退出应用。
4. **标签页关得掉 App 关不掉**（v1.0.28 起）：App 退到后台被系统杀掉进程、或从最近任务
   划掉后再打开，开着的标签页（网址和标题）原样恢复，连停在哪一页都记得；恢复出来的
   后台页第一次切过去才真正加载（点「标签页」只是看卡片，不会加载）。**只有你亲手点 ✕
   关掉最后一个标签页**，下次打开才是干净的新标签页——按返回键退出、被系统清掉都保留
   （v1.0.35 起；v1.0.28~v1.0.34 里按返回键退出会把标签页记录一起清掉，这是那个版本
   「标签页老是没了」的原因）。退到后台时记录是同步落盘的，写完就被杀也不会丢。
5. **回到前台自动刷新起始页**（v1.0.35 起）：App 还活着时 WebView 里是上次那份页面
   （进程没被杀就不会重新请求），所以回到前台超过 3 秒、且当前这一页属于**起始站点**
   （设置里那个网址的域名）时，自动重新加载一次，看到的就是最新内容。只刷起始站点：
   日历表单、学习卡片这种可能正填着一半的页面不动；从设置/已下载/选文件这些我们自己的
   页面回来也不刷。

### 方式 M：网页里「打开 App」的链接（v1.0.36 起）

网页里点一条要跳别的 App 的链接（豆瓣的 `douban://douban.com/movie/35465232`、`weixin://…`，
或 Chrome 那套 `intent://…#Intent;scheme=…;package=…;end`），App 会在底栏上方弹一条提示：

```
[图标] 此网站请求打开 App     打开  ✕
       豆瓣
```

点「打开」才真的跳走（和 Chrome 一样，不是偷偷跳）；点 ✕ 关上。几个细节：

- **认得出目标 App** 时提示条显示它的真实名字和图标；认不出（没装、或只认识 scheme）就显示
  按 scheme 猜的中文名（豆瓣/微博/知乎…），再不认识显示「其他应用」——点「打开」会告诉你
  「手机上没装能打开这个链接的应用」。
- `intent://` 里带的 `browser_fallback_url`（网页说「App 打不开就用浏览器打开这个网址」）
  会在打不开时自动在当前标签页里打开。
- 页面脚本自己在背后乱跳的**陌生** scheme（统计、拉活用的 `adssdk://` 之类）不会弹条，
  日志里记一句 `忽略脚本跳转的 scheme: …`；`about:` / `data:` / `blob:` / `file:` 这些网页
  自己的伪协议照旧交给 WebView，不当成「打开别的 App」。
- 清单里的 `<queries>` 同样不能删：**没有这些 scheme 的声明，Android 11+ 的包可见性会让
  `resolveActivity` 直接返回空、`startActivity` 抛 `ActivityNotFoundException`**——症状就是
  「点豆瓣链接完全没反应」（旧的代码把异常吞掉了，所以连报错都看不到）。要支持新站点，
  在 `<queries>` 里照格式加一条 `<data android:scheme="…" />` 即可。
- 注入页面的脚本原本会把所有 `target="_blank"` 的链接转交系统浏览器（方式 I 的「新窗口
  打开」）；而日历的豆瓣条目恰恰是 `<a data-app="douban://…" target="_blank">`——capture 相
  先劫持走，页面自己的处理器看到 `defaultPrevented` 就放弃，结果点了只会跳到系统浏览器的
  **网页版**。现在这类带 `data-app`（网页自己声明的「开 App」链接）会被放行给页面处理器，
  由它发起 `douban://` 再被 App 拦下弹条。

### 方式 N：记住登录 & 语音直达某个页面（v1.0.39 起）

**① 记住网站登录（Cookie）。** 登录过的站点（包括政务站点那种登录时要在好几个域名之间跳的统一认证）
不用每次重新登录。App 做三件事：页面加载完和退到后台时把 Cookie 落盘、显式接受第三方 Cookie
（跨域名登录必需）、长按底栏「刷新」**只清缓存不再清 Cookie**（旧版本顺手把 Cookie 一起清了，
所以"强制刷新一下 = 所有网站都退出登录"）。要清登录状态是设置页里一个单独的按钮
「清除所有网站登录状态（Cookie）」——只清 Cookie，不动网页缓存。

**② 语音快捷指令。** 设置 → 「语音快捷指令」：填「名称 + 网址」加一条，比如名称「乘车码」网址填
乘车码页面。加好之后有三条路进这个页面：

| 怎么触发 | 前提 | 效果 |
| --- | --- | --- |
| 长按桌面上的 App 图标 → 点「乘车码」 | 无 | 直接打开这条网址（动态快捷方式，最多 5 条） |
| 对小艺说「打开 + 应用名 + 乘车码」 | 手机助手支持**按快捷方式名**直达 | 直接打开这条网址 |
| 对小艺说「打开 + 应用名」（只开 App） | 设置里把「语音助手打开时」设成这条 | App 认出来是助手唤起的，直接进这页 |

第三条路把「设置 → 语音快捷指令 → 语音助手打开时：直接进『乘车码』」设好即可：App 每次启动都会
看一眼"这次是谁把我叫起来的"（referrer 包名，华为助手是 `com.huawei.vassistant`；桌面图标点开是
`com.huawei.android.launcher`，不会误跳），设置页最下面会显示上次是谁唤起的，好核对。
外部工具也能用它：`webshare://voice/<条目 id>` 这个深链（清单里注册了 `webshare` scheme）一开就直达
对应页面。

## 技术说明

### DoH 实现原理

当 DoH 开启时，应用通过 `WebViewClient.shouldInterceptRequest` 拦截 WebView 的 GET 请求，使用 OkHttp 发起请求并通过自定义 DNS 解析器（`DohDnsResolver`）进行域名解析。DNS 查询通过 HTTPS 发送到配置的 DoH 服务器，实现加密 DNS 查询。

**注意事项：**
- WebView 的 `shouldInterceptRequest` 仅拦截 GET 请求（主页面与子资源加载）
- JS 发起的 POST 等请求：v1.0.11 起通过自动注入脚本接管 fetch/XHR，原生请求失败时走 App 内部 DoH 通道（见"方式 E"）
- Cookie 通过 `CookieManager` 在 WebView 和 OkHttp 之间自动同步
- 子资源（图片/视频/脚本）拦截失败时返回 `null` 交给 WebView 处理，**不会**塞 HTML 错误页——
  否则 `<video>` 会把网络错误报成"解码失败"，页面的重试/转码逻辑会被带偏
- **主框架（主页面）的 301/302 不由 App 内部跟完，而是整条导航交回 WebView**（v1.0.41 起）：
  代理内部把跳转跟完会把页面挂在**跳转前的地址**下，页面里的相对路径（`js/chunk-vendors.js`
  这种）就会解析到错的目录——厦门乘车码的登录页正是这么白屏的（JS 取回来是 200 +
  `application/json`，内核按「MIME 不可执行」拒绝执行）。`WebResourceResponse` 本身不允许
  3xx 状态码，所以做法是「发现要跳转就返回 `null` 不拦」，让 WebView 照标准跳（地址栏、
  相对路径基址、同域判断才对）。子资源仍照旧内部跟跳。代价：被交回的**这一跳**不走自定义
  DNS——只认自定义 DNS 的站点若又恰好整站跳转，可能连不上（日常站点不受影响）
- 页面是 `http://` 源、而站点实际只开 TLS（端口 7777）时，网页原生 POST 必然被重置，
  此时全靠注入脚本的桥接兜底；桥接内部会在 http / https 之间自动回退
- 下载与上传走 `HttpEngine`（流式，不整包进内存）；下载交给前台服务，
  应用切到后台或息屏也能继续
- 已开启 `setWebContentsDebuggingEnabled`，可用 `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`
  连 Chrome DevTools 调试页面

### 网页视频：能力上报与录像修复

**能力上报**（`MediaCaps.kt` + `shim.js`）——`MediaCodecList(ALL_CODECS)` 里跳过 encoder，
按 `isHardwareAccelerated`（Android 10+）/ 解码器名字（更早版本）判断 H.265 是硬解还是软解，
结果给注入脚本；脚本只在「设备完全没有 H.265 解码器」时才把 `canPlayType` /
`MediaSource.isTypeSupported` 的 hevc/h265 答案改成空串 / false。VP9（libvpx）和 AV1（dav1d）
是 Chrome 自带软解，不能跟着一起降级，否则本来能播的 B 站 AV1 视频反而播不了。

**时间轴修复**（`MediaRepair.kt`）——**只改文件自己的采样表，采样数据一个字节都不动**：
解析 moov 里的 stts / stsz / stsc / stco / stss（有 ctts 也处理），判定规则是「相邻样本时间差
`0 ≤ Δ < 1000µs` 且这种样本 ≥ 3 个」算塌陷，从塌陷的第一个样本起整段丢弃，然后把每张表截断
（盒子总长保持不变、多余位置补 0，所以 mdat 里样本的字节位置和 stco 里的偏移一个都不用改），
改 mdhd / tkhd / mvhd 时长，最后按保留样本的末字节把 mdat 尾段砍掉。效果等同
`ffmpeg -t <截断点> -c copy`，既不依赖 ffmpeg，也不经过 `MediaMuxer`。
判定阈值不能放宽：B 帧会导致 PTS 逆序（差值为负），把负差也算成损坏会误伤 B 帧视频——
早期一条 `Δ < max(2000, 自然间隔/4)` 的规则就曾把 321 个健康视频里的 296 个砍成残废，
现在这条规则在全站 349 个视频上跑，命中的 24 个全部是门锁录像、零误伤。
修完立刻用同一个解析器把结果读回来自检（每条轨道的样本数与预期一致、首帧仍在 0、时间轴不再塌陷），
通过才写缓存；缓存按 URL 的 MD5，目录带规则版本号（`video-repair-r4`），换规则自动失效。
每次尝试都会在缓存目录留一个 `<md5>.trace.txt`，记录解析与判定过程，
`adb pull` 出来就能回答「为什么这个视频没修 / 修掉了多少帧」。

**为什么不用 MediaExtractor + MediaMuxer 重封装**（v1.0.19 之前的两次返工）：

1. `MediaExtractor` 枚举 + `MediaMuxer` 重封装：实测这类文件上 `MediaExtractor` 会漏掉文件自己的
   第 0 号样本（PTS=0 的那个关键帧），开头几帧的 SYNC 标志也报成 0；`MediaMuxer` 见首个视频样本
   不是关键帧，就把它之前的样本整段丢掉——修出来的文件视频从 0.889s 才开始、音频却从 0 开始，
   音画错位 0.889s，开头 11 帧真实画面永久丢失。
2. 改成自己读采样表、只把样本数据交给 `MediaMuxer` 写：`MediaMuxer` 对 AVC/HEVC 样本会做
   「Annex-B → 长度前缀」的转换，而文件里的样本本来就是长度前缀格式，找不到起始码，
   于是整段被当成一个 NAL 又套了一层长度前缀（每帧多 4 字节）。实测 ffmpeg 解出 0 帧、
   WebView 77 帧里只解出 2 帧——画面根本没出来，只有声音在走。
   现在改成原地改表，以上两种平台行为都不再影响结果。

**踩过的坑：整份交付 vs Range / 206**——站点对本地录像按 `Range` 回 206，而 WebView 的媒体管线
拿到一串被截断 / 反复重取的 206 时，会在第一个 GOP 之后报 `MEDIA_ERR_DECODE`（实测：同一份字节
用不支持 Range 的服务器整个 200 发过去能完整播完，用 206 分段就只能播 0.9 秒）。所以现在对
「从 0 开始」的视频请求，主动把 `Range` 放大成 `bytes=0-<64MB-1>`，拿到整份后以 **200 + 完整 body**
交回去，并把 `Content-Range` / `Content-Length` / **`Accept-Ranges`** 一起删掉。`Accept-Ranges`
不能留：留着它 WebView 会在拿到整份之后又按 Range 回头再取一遍（实测 4.6MB 的文件整份交付后
又被请求了 `196608-` 那一段），而二次按 Range 取到的分段正是播不动的根源；去掉它，行为等同于
一台不支持 Range 的普通服务器。文件超过 64MB 时只拿得到一段，这时照原样按 Range 交回，不冒险吃内存。

改写响应体（录像修复）时同样必须删掉描述原始 body 的头部，否则 FFmpegDemuxer 会按错误的长度读，
报 `PIPELINE_ERROR_READ: FFmpegDemuxer: data source error`（`MEDIA_ERR_SRC_NOT_SUPPORTED`，
看起来像格式不支持，其实只是长度对不上）。

**下载路径**（`DownloadService.kt`）——下完后读回文件跑同一套 `MediaRepair`，修好了就覆盖写回，
识别不了 / 不需要修（返回 null）就保持原样，绝不动健康文件。

**分享进来的文件**（`MainActivity.kt` + `DohWebViewClient.kt`）——`ACTION_SEND` / `SEND_MULTIPLE`
的 `EXTRA_STREAM` 取 `content://`：先登记进 `WebAppInterface` 的「刚选过的文件」表（multipart 上传
按 文件名+大小 找内容）。**自动上传**走这样一条路：文件挂到同源虚拟地址
`/__webshare__/shared/<token>`（拦截器本地应答，不落盘、不出网，token 每次分享都换），页面加载完
注入脚本 `fetch` 回来包成 `File`，塞进页面第一个 `<input type="file">` 并派发 `change` ——
站点自己的上传逻辑照常跑，用户零点击；页面若想接管，声明 `window.webshareAutoUpload(files)` 即可。
注入结果写进 `window.__webshareAutoUploadResult`，App 读回来决定提示（`dispatched` / `hook` /
`no-input` / `error: ...`）。兜底：页面开文件选择器时把分享的文件直接回填（`onShowFileChooser`，
类型不匹配才回退到普通选择器）。`EXTRA_STREAM` 既可能是单个 `Uri` 也可能是 `List`，直接从 extras
里按类型取，不走 `getParcelableExtra`（后者在类型不符时可能抛 `ClassCastException`）。读相册文件要
`READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`（13+）或 `READ_EXTERNAL_STORAGE`（≤12）——只声明
`READ_MEDIA_IMAGES` 时 MediaProvider 会对视频 uri 抛
`SecurityException: com.webshare.app has no access to content://media/...`，清单里补齐、
首次分享时申请一次。

### 最低系统要求

- Android 7.0 (API 24) 及以上
- 目标 SDK: Android 14 (API 34)

## 许可证

本项目可自由使用和修改。
