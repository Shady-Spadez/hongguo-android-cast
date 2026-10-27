# Android 应用内更新与发布

## 使用

0.12.11/code34首版包含更新器；0.12.12/code35修复首次开启安装来源权限后返回的续装状态。0.12.10及更早版本先手动覆盖安装一次；0.12.11及后续版本可在关于页检查在线更新。自动检查按24小时节流，播放中不弹提示，稍后不反复提示同一版本。下载和系统安装均需用户确认。

0.12.11首次开启安装来源权限后如停在等待状态，返回关于页重新检查更新，再点继续安装；已下载并验证的包会保留。0.12.12修复该返回分支，重新校验APK并保存记录后进入系统安装器。

当前发行0.12.14/code37，修复覆盖升级后的缓存恢复状态误报，保留0.12.13的账号、收藏与进度同步修复；沿用既有更新通道和兼容证书。0.12.12/code35及0.12.13/code36可直接从关于页检查并确认升级，历史Release资产保留不替换。

手机包`io.github.jackotom.hongguo.android`、TV包`io.github.jackotom.hongguo.android.tv`分开，不能互换。更新保留现有安装证书；更换证书会破坏覆盖更新链，不通过卸载清数据处理。

## 固定渠道

- 手机：https://raw.githubusercontent.com/skydeadpeng/hongguo-android-cast/main/android/update-phone.json
- 电视：https://raw.githubusercontent.com/skydeadpeng/hongguo-android-cast/main/android/update-tv.json

只接受自有仓库不可变`releases/download/v<version>/Hongguo-<version>-<phone|tv>-release-compatible.apk`或`-release.apk`。不读取aston项目或桌面上游清单，不允许任意用户输入更新地址。

## 本地构建与生成清单

```sh
bash scripts/build-android.sh --phone-release-compatible
bash scripts/build-android.sh --tv-release-compatible
node scripts/generate-android-update.mjs --apk dist/android/Hongguo-0.12.14-phone-release-compatible.apk --device phone --output dist/android/update-phone.json
node scripts/generate-android-update.mjs --apk dist/android/Hongguo-0.12.14-tv-release-compatible.apk --device tv --output dist/android/update-tv.json
```

版本随实际发布调整，生成器从已签名APK读取字段，拒绝错包、debug包、错签名和非规范文件名，重新打开生成清单确认SHA256。使用SDK Build Tools 35.0.0的aapt与apksigner。不包含上传动作。

清单字段：schemaVersion=1、channel=android-compatible、flavor、packageName、versionCode整数、versionName、minSdk、apkUrl、size、sha256，可选notes。清单自报信息不能替代实际APK签名和包信息校验。

## 发布次序

1. 先完成回归、手机/TV签名构建并核验与旧安装证书一致。
2. 从最终APK生成清单和校验和，保管现有私钥，不提交私钥或凭据。
3. 上传不可变版本Release资产；同一versionCode不替换不同内容。
4. 确认资产下载及散列正确后，最后更新`android/update-phone.json`、`update-tv.json`稳定清单。稳定清单不应先于可用APK发布。
5. 真实手机及TV分别从旧版走应用内安装，检查未知来源权限返回、取消/重试及数据保留；ADB覆盖安装不替代这条业务验收。

## 安装与数据

更新下载进入私有updates目录，不进入播放缓存。下载取消/错误清理半成品；完整文件安装前重新校验准确size/SHA、真实包名/versionCode/versionName/SDK/签名；只读content URI交给系统安装器。打开安装器并不等于安装成功，以新版本重启后的实际版本为准。

安装前有业务互斥和同步落盘checkpoint。活跃播放器、投屏/合并服务、手动下载/准备任务存在时先结束业务再重试；记录写盘失败不启动安装。失败、取消或权限拒绝不清账号历史、下载、设置或视频缓存。
