# 网页分享助手 (WebShareApp)

一个 Android 应用，用于挂载指定网页并接收系统分享内容。专为华为手机侧边快捷栏（智慧多窗）设计，可将分享的网址/图片传递给挂载的网页进行处理。

## 功能特性

- **WebView 网页挂载**：在应用内加载指定网页
- **接收分享内容**：接收来自其他应用分享的文本（网址）和图片
- **设置界面**：可配置打开的网页地址
- **安全 DNS (DoH)**：支持 DNS-over-HTTPS，可自定义 DoH 服务器，防止 DNS 劫持
- **JavaScript 接口**：网页可通过 JavaScript 获取分享内容

## 项目结构

```
WebShareApp/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/webshare/app/
│       │   ├── MainActivity.kt          # 主界面：WebView + 分享接收
│       │   ├── SettingsActivity.kt       # 设置界面
│       │   ├── SettingsManager.kt        # 设置管理
│       │   ├── DohDnsResolver.kt         # DoH DNS 解析器
│       │   ├── DohWebViewClient.kt       # 支持 DoH 的 WebView 客户端
│       │   └── WebAppInterface.kt        # JavaScript 接口
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
- DoH 仅对 GET 请求生效（Android WebView 的 `shouldInterceptRequest` 不提供 POST 请求体）
- POST 请求会使用系统默认 DNS
- Cookie 通过 `CookieManager` 在 WebView 和 OkHttp 之间自动同步

### 最低系统要求

- Android 7.0 (API 24) 及以上
- 目标 SDK: Android 14 (API 34)

## 许可证

本项目可自由使用和修改。
