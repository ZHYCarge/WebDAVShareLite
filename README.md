# WebDAV 快传

> 一个只做"从相册/文件管理器分享文件到 WebDAV 服务器"这一件事的极简 Android 工具。

[![Android](https://img.shields.io/badge/Android-4.1%2B-brightgreen)](https://developer.android.com/about/versions)
[![minSdk](https://img.shields.io/badge/minSdk-16-orange)](https://developer.android.com/about/versions)
[![Dependency](https://img.shields.io/badge/dependencies-0-informational)](#)
[![Language](https://img.shields.io/badge/Java-8-red)](#)

---

## 这是什么

一台旧安卓手机（Android 4.x）想把照片、文件备份到家里的[OpenList](https://github.com/OpenListTeam/OpenList)，翻遍应用市场却发现：

- 主流网盘客户端（Nextcloud、Dropbox、各类网盘）**早就不再支持 Android 7 以下**；
- 通用 WebDAV 客户端（如 DAVx⁵、WebDAV Nav 等）要么体积几十 MB、要登录一堆账号、要么是一个完整的文件管理器，功能用不上一半；
- 它们都在后台常驻同步、耗电、占存储，而你只是想**拍完照顺手丢几张到服务器**。

**WebDAV 快传**就是为这个场景写的：一个 APK 几乎不占空间，没有任何后台服务、没有账号体系、没有自己的文件浏览界面——它寄生在系统的"分享"菜单里。你在相册选照片 → 点分享 → 选"WebDAV 快传" → 选个云端目录 → 完成。

## 痛点对比

| | 通用网盘客户端 | 通用 WebDAV App | **WebDAV 快传** |
|---|---|---|---|
| 最低系统 | Android 8 ~ 10+ | Android 7+ | **Android 4.1** |
| APK 体积 | 30 ~ 100 MB | 10 ~ 30 MB | **< 100 KB**（源码编译） |
| 后台常驻 / 同步 | 有 | 部分有 | **完全没有** |
| 自己的文件管理器 | 有 | 有 | **无**（直接用系统相册/文件） |
| 需要存储权限 | 一般需要 | 需要 | **不需要** |
| 三方依赖 / SDK | 一大堆 | 有 | **零** |
| 使用方式 | 打开 App → 登录 → 浏览 → 上传 | 打开 App → 浏览 → 上传 | **系统分享 → 选目录 → 上传** |

## 功能

- **接收系统分享**：单文件 `ACTION_SEND`、多文件 `ACTION_SEND_MULTIPLE`，任意 MIME 类型。
- **在线选云端目录**：`PROPFIND Depth: 1` 列出子文件夹，可逐层进入、返回上一级。
- **云端新建文件夹**：`MKCOL` 一键建目录，创建后自动进入。
- **分块流式上传**：`PUT` + Chunked Transfer-Encoding，不把文件读进内存，老手机传大图也不 OOM。
- **防重名**：可选给文件名加 `yyyyMMdd_HHmmss_SSS_序号_` 前缀，配合 `If-None-Match: *` 避免覆盖。
- **HTTP / HTTPS**：HTTPS 走系统证书链并校验主机名；老 Android 4.x 会手动协商 TLS 1.2。*(作者没有尝试进行过HTTPS，如有错误请提PR或者下载源码后自行修复)*
- **诊断日志**：每一步都有带时间戳的日志，一键复制到剪贴板，方便反馈问题。
- **零权限**：只声明 `INTERNET`，连 `READ_EXTERNAL_STORAGE` 都不要，通过 `ContentResolver` 读 `content://`。

## 使用

### 1. 首次配置

打开 App，填入你的 WebDAV 服务器信息，例如：

```
URL:      http://192.168.1.10:5244/dav/
用户名:   your-name
密码:     your-password
```

勾选"文件名前加时间戳"可避免同名覆盖。点**保存配置**。

> 账号密码保存在 App 私有目录的 SharedPreferences，**明文存储**，请只在自己的设备上使用。

### 2. 上传文件

1. 在系统相册或文件管理器里选中照片/文件；
2. 点 **分享 → WebDAV 快传**；
3. App 自动弹出云端根目录列表，点进想放的子文件夹（也可"＋ 新建文件夹"）；
4. 点 **✓ 上传到当前文件夹**；
5. 进度条走完，Toast 提示成功/失败数量。

### 3. 出错怎么办

上传失败时，界面下方灰底日志区会打印详细原因（HTTP 401 / 403 / 404 / 409 / 412 / 413 等都有中文人话解释），点**复制诊断日志**贴出来即可。

## 服务端要求

- 标准 WebDAV 服务，允许 `PROPFIND`、`MKCOL`、`PUT` 三个方法；
- OpenList / Alist 用户请给账号开启 WebDAV 浏览、建目录、上传权限；
- 反向代理请不要拒绝 Chunked Transfer-Encoding，否则 PUT 会报 HTTP 411/501；
- HTTP 明文模式请只在可信局域网使用，公网请配置 HTTPS。

## 构建环境要求

- Android Studio Hedgehog 或更新
- **JDK 11**
- Android SDK Platform 33
- Gradle 7.4 / AGP 7.3.1
 
## 技术要点

- **无 AndroidX、无 Kotlin、无任何三方运行时库**，全部用 Android Framework 原生 API。
- `HttpURLConnection` 在很多 Android 版本上不接受 `PROPFIND` / `MKCOL` 方法，因此目录操作通过手写 `Socket` / `SSLSocket` 完成 HTTP/1.1 请求，文件上传仍走 `HttpURLConnection`。
- 目录响应体硬上限 4 MB，防止老手机列大目录 OOM。
- PUT 使用 `setChunkedStreamingMode(16 * 1024)`，不依赖相册报告的文件长度元数据（修复了部分相册 `OpenableColumns.SIZE` 与实际流长度不一致导致的 `ProtocolException`）。
- 不跟随 3xx 重定向，避免 Basic Auth 凭据跨域泄漏。
- 不读图库数据库、不申请存储权限，纯粹通过 `ContentResolver.openInputStream(uri)` 拿分享进来的文件流。

## 工程结构

```
app/src/main/java/com/zhycarge/webdavsharelite/
  MainActivity.java    # 分享入口、配置、目录选择、新建与上传调度
  WebDavBrowser.java   # PROPFIND / MKCOL，手写 Socket + WebDAV XML 解析
  WebDavClient.java    # PUT 上传、文件名处理、Basic Auth
```

## 已知限制

- **只支持上传，不支持下载、删除、重命名、浏览已有文件**（列目录只显示文件夹）。
- **无后台上传**：上传过程中请把 App 留在前台，切走可能中断。
- 账号密码明文存储在本地，不要在公共/二手手机上使用。

## 协议

MIT License — 随意使用、改造、二次分发。

 
