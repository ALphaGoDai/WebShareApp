# 网页分享助手 (WebShareApp)

一个 Android 应用，用于挂载指定网页并接收系统分享内容。专为华为手机侧边快捷栏（智慧多窗）设计，可将分享的网址/图片传递给挂载的网页进行处理。

## 功能特性

- **WebView 网页挂载**：在应用内加载指定网页
- **接收分享内容**：接收来自其他应用分享的文本（网址）和图片
- **设置界面**：可配置打开的网页地址
- **下载到手机**：网页里的下载按钮 → 确认框选目录 → 走 App 自己的 DNS/TLS 通道下载，带进度通知
- **剪贴板可用**：`http://` 源下网页的「复制 / 粘贴」按钮也能正常工作（桥接 App 剪贴板）
- **文件上传**：网页 `<input type="file">` 可用，带文件的表单由 App 流式组装上传
- **新窗口链接交给系统浏览器**：`window.open` / `target="_blank"` 不再顶掉挂载的页面
- **安全 DNS (DoH)**：支持 DNS-over-HTTPS，可自定义 DoH 服务器，防止 DNS 劫持
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

1. 打开应用，点击右下角的设置按钮
2. 在"网页地址"中输入你要挂载的网页 URL
3. 保存设置

### 2. 配置分享内容传递

分享内容有两种传递方式：

**方式 A：URL 占位符（推荐）**

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

## 技术说明

### DoH 实现原理

当 DoH 开启时，应用通过 `WebViewClient.shouldInterceptRequest` 拦截 WebView 的 GET 请求，使用 OkHttp 发起请求并通过自定义 DNS 解析器（`DohDnsResolver`）进行域名解析。DNS 查询通过 HTTPS 发送到配置的 DoH 服务器，实现加密 DNS 查询。

**注意事项：**
- WebView 的 `shouldInterceptRequest` 仅拦截 GET 请求（主页面与子资源加载）
- JS 发起的 POST 等请求：v1.0.11 起通过自动注入脚本接管 fetch/XHR，原生请求失败时走 App 内部 DoH 通道（见"方式 E"）
- Cookie 通过 `CookieManager` 在 WebView 和 OkHttp 之间自动同步
- 子资源（图片/视频/脚本）拦截失败时返回 `null` 交给 WebView 处理，**不会**塞 HTML 错误页——
  否则 `<video>` 会把网络错误报成"解码失败"，页面的重试/转码逻辑会被带偏
- 页面是 `http://` 源、而站点实际只开 TLS（端口 7777）时，网页原生 POST 必然被重置，
  此时全靠注入脚本的桥接兜底；桥接内部会在 http / https 之间自动回退
- 下载与上传走 `HttpEngine`（流式，不整包进内存）；下载交给前台服务，
  应用切到后台或息屏也能继续
- 已开启 `setWebContentsDebuggingEnabled`，可用 `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`
  连 Chrome DevTools 调试页面

### 最低系统要求

- Android 7.0 (API 24) 及以上
- 目标 SDK: Android 14 (API 34)

## 许可证

本项目可自由使用和修改。
