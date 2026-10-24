# 红果 Android Cast

**手机选剧，电视接着看。** 面向 Android 手机与 Android TV 的独立客户端，支持局域网 DLNA 视频投屏：手机选电视、选集控播，同季自动连播，提前缓存后 5 集，看过的临时缓存自动清理。

[下载手机 / TV 版](https://github.com/skydeadpeng/hongguo-android-cast/releases) · [Android 详细说明](ANDROID.md) · [账号与观看记录同步](docs/ANDROID-ACCOUNT-SYNC.md) · [上游项目](https://github.com/jackotom/hongguo-drama)

## 为大屏连续观看而做

- **手机就是遥控器**：发现局域网内的 DLNA 电视或盒子，手机上直接切集、播放 / 暂停、拖动进度，也可停止投屏回到手机继续看。
- **本季连着看**：手机、TV 本机播放及手机投屏均支持同一季内自动下一集；保留横向分季 Tab 与各季观看记录，当前季播完停止，不自动跳到下一季。
- **后 5 集提前准备**：投屏时预取后续 5 集，减少换集等待；按前一集、当前集、后 5 集维护窗口，自动清理窗口外的已看临时缓存，不删除手动下载。
- **可选合并投屏**：把本季连续 2 / 3 / 5 集准备成一段 MP4 再投出，减少接收端换片次数；编码配置不兼容时回退逐集投屏，不进行转码。
- **离开再回来，知道看到哪**：本机保存集数与播放位置；可选部署 Go + Gin 同步服务，手机和 TV 登录同一账号后同步观看记录与进度，服务器地址由你填写。
- **照顾取源失败**：支持画质选择、备用源、重新取源及自动降画质重试。0.12.8 补齐网页预览识别、完整源回退与取源等待期限，不把预览误当整集。

> 5 集是预取目标，不代表每次都已缓存完成；受片源可用性、网络、单集 96 MiB 和投屏缓存 768 MiB 预算影响。手机本机播放沿用自己的预取策略。

## 下载与安装

当前 Android 版本：**0.12.8**。从 [Releases](https://github.com/skydeadpeng/hongguo-android-cast/releases) 选择对应 APK：

| 版本 | 文件名 | 用途 |
| --- | --- | --- |
| 手机 | `Hongguo-0.12.8-phone-release-compatible.apk` | 手机触控、本机播放、DLNA / 浏览器投屏 |
| TV / 盒子 | `Hongguo-0.12.8-tv-release-compatible.apk` | 电视独立播放、遥控操作、账号与进度同步 |

- Android **8.0+**（API 26），使用设备系统 WebView 与原生视频解码器。
- 手机与 TV 使用不同应用 ID。TV 版是独立播放器，安装 TV APK 不会自动把电视变成 DLNA 接收器。
- `release-compatible` 为非调试 release 构建，但沿用既有安装证书，方便旧用户覆盖安装保留记录；不是独立生产签名。自行构建时，只有使用同一证书才能覆盖原包。
- [桌面版原说明](README.desktop.md)与 Electron 桌面源码仍保留，当前基线为 2.3.0；Android 独立版本号为 0.12.8，只按需移植上游取源修复，未整体升级为桌面 3.0。

## 三步开始 DLNA 投屏

1. **连同一局域网**：手机与电视连接同一 Wi-Fi / 局域网，避免访客网络或设备隔离；在电视上打开支持 DLNA 的无线投屏接收入口。
2. **手机选电视**：在手机版打开剧集播放器，点击「投屏」，搜索并选择电视。片源先在手机准备，随后经局域网交给电视播放；这不是手机屏幕镜像。
3. **手机控播放**：直接选集或上一集 / 下一集，按需打开「连播」。投屏菜单可管理连接、重试、查看诊断或停止；通知栏也能停止投屏。

### 想减少频繁换片？试试合并投屏

在投屏菜单选择每段 **2 / 3 / 5 集**，停止后重新连接 DLNA 生效。手机会先准备本季连续分集，再做样本级无损拼接；首次准备需要更久，不跨季拼接，也不新增编码转换。各集解码配置不一致时，自动回到逐集模式。

### 电视兼容性

接收端需要支持 **DLNA MediaRenderer / AVTransport**。仅有 Miracast 镜像或仅有 Google Cast 不等于支持 DLNA；搜索、控播、定位和片尾状态表现因电视及接收应用而异。没有匹配所有型号的通用保证，遇到问题可复制应用内的投屏诊断。合并投屏的实体设备兼容性仍需实际验证。

## Mac、iPhone、iPad：浏览器也能接收

手机版还保留「**投屏 → Mac / iPhone / iPad · 浏览器接收**」：

1. 两端连接同一局域网，安卓手机开启浏览器接收。
2. 用 Safari 打开手机显示的接收地址，输入本次配对码。
3. 在接收页点「开始播放」，随后用手机或接收页控制播放与同季连播。

视频仍走「**片源 → 安卓手机缓存 → 局域网 → 浏览器**」，无需另装 Mac 后端。接收页应保持可用，避免系统休眠或后台挂起。

这不是安卓原生 AirPlay，也不是系统屏幕镜像。Safari 提供 AirPlay 按钮时，可由 Safari 再转投；项目未实现安卓端 Apple TV 发现与配对。Safari / Apple TV 的实体设备兼容性仍需验收。完整说明见 [Android 文档](ANDROID.md)。

## 可选：自己的账号，自己的同步服务器

**不登录也能播放与投屏。** 希望多设备接着看时，可自行部署 [Go + Gin 账号服务](server/login/README.md)，再在手机与 TV 的「关于 → 账号与同步」填写同一个 HTTPS 服务地址并登录。

- 同步每部剧的真实分集与播放位置，离线先本地保存，重新连接后再同步。
- 同一账号多端更新采用服务端修订号处理冲突，不直接用“集数更大”覆盖重看进度。
- 账号服务器**只同步观看记录和进度，不转发媒体、不上传视频与缓存**；收藏、搜索历史和手动下载仍保留在各设备本机。
- 登录令牌仅发给填写的固定账号服务，不交给片源或电视。

部署与升级见 [服务端说明](server/login/README.md)，客户端操作见 [账号同步说明](docs/ANDROID-ACCOUNT-SYNC.md)。

## 从源码构建

准备 Node.js **22.12+**、npm、JDK 17、Android SDK Platform 35，以及 Gradle 8.13 或项目 Gradle Wrapper。在仓库根目录执行：

```sh
npm ci

# 手机与 TV debug 包
npm run build:android

# 非调试 release 构建，使用本机兼容证书
npm run build:android:phone:release-compatible
npm run build:android:tv:release-compatible
```

结果写入 `dist/android/`。兼容证书默认读取 `~/.android/debug.keystore`，也可通过 `HONGGUO_COMPAT_KEYSTORE` 指定；新证书与发布包证书不一致时，需要卸载后安装，卸载前请保存所需数据。

正式分发可使用自己的外部签名，设置 `HONGGUO_RELEASE_KEYSTORE`、`HONGGUO_RELEASE_STORE_PASSWORD`、`HONGGUO_RELEASE_KEY_ALIAS`，以及可选的 `HONGGUO_RELEASE_KEY_PASSWORD`，然后执行：

```sh
npm run build:android:phone:release
npm run build:android:tv:release
```

签名私钥与密码不提交到仓库。依赖已缓存时可加 `GRADLE_OFFLINE=1` 离线构建。更多构建、测试与版本记录见 [ANDROID.md](ANDROID.md)。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `src/android/` | 手机 / TV 界面、桥接与数据源解析 |
| `android/` | 原生播放器、DLNA、浏览器接收、缓存与本机记录 |
| `server/login/` | 可独立部署的 Go + Gin 账号与进度同步服务 |
| `src/native/` | 与桌面版共享的部分取源实现 |
| `ANDROID.md` | Android 版本记录、功能说明与验证情况 |

## 来源与许可

基于 [jackotom/hongguo-drama](https://github.com/jackotom/hongguo-drama) 追加 Android 手机、TV 与局域网投屏实现，感谢上游作者。原项目及第三方声明继续保留，采用 [GPL-3.0](LICENSE)；请同时阅读 [NOTICE](NOTICE) 与 [第三方声明](THIRD-PARTY-NOTICES.md)。

这是社区开源修改版，不是来源平台的官方客户端。剧集、清晰度和可播放范围由片源决定；自动化验证不等同于全部手机、电视或接收端上的实机播放验收。
