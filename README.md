# CM Hook

网易云音乐（`com.netease.cloudmusic`，实测 9.5.96 / 9005096）的本机 LSPosed 模块：协议观测 + 界面整理 + 消息防撤回台账。

## 功能

- **去开屏广告（三层拦截）**：网络层直接清空广告数据（`ad/loading/get` / `bidget` / `current` 的 `ads[]`），
  渠道无广告闸门伪装 + `LoadingAdManager` / `LoadingAdActivity` 兜底；冷启动直达首页
- **抽屉 VIP 挽回卡自定义**：侧边抽屉顶部的 VIP 营销卡整卡替换为自定义图片——设置面板内相册选图、
  预览、裁切编辑页（拖动/双指缩放按卡片比例裁切），可一键恢复占位
- **日志白名单**：协议采集只落本机日志文件，LSPosed 日志仅镜像功能事件，不再刷爆日志缓冲区
- **消息防撤回台账**：私信聊天页右下角「撤回 N」胶囊（可拖动、位置持久），点开只列**本会话**被撤原文；
  记账走三源：① 列表消失 ② 本地库窗口缺失（`private_chat_message_db`）③ 库条目消失。
  ⚠️ 只有当本地 database 中存有该消息时才可查看被撤回内容——消息未同步到本机、或本地数据被清理时无法查看
- **首页内容清理**：`推荐` 只保留白名单块（`home_clean_keep`，默认 每日推荐/猜你喜欢/根据你喜爱推荐），
  锚点块命中时按锚点切；配套清 MMKV 块缓存（`SP_MIX_CONTAINER` / `HOME_RECOMMEND_PAGE_*`），并可在 MMKV 读取时过滤
- **播客「为你推荐」清理**、**关注页「乐迷团」隐藏**、**长按顶栏搜索区进听歌识曲**
- **独白 HUD**：把接口调用翻译成人话的悬浮窗（可拖动/折叠/✕关闭，仅目标 App 前台可见）
- **频道精细控制**（保留白名单）、**App 探测清单**、**DexKit 锚点自检**（L0 健康检查 / L1 按需预热 / L2 手动重建）
- 设置入口：目标 App 的**设置页右下角**「⚙ CM Hook」胶囊

## 构建

依赖不随仓库提供（自行获取）：

| 文件 | 来源 |
|---|---|
| `libs/dexkit.jar` | DexKit 2.2.0 AAR 里的 `classes.jar` |
| `libs/kotlin-stdlib.jar` | `org.jetbrains.kotlin:kotlin-stdlib:1.5.0` |
| `libs/flatbuffers.jar` | `com.google.flatbuffers:flatbuffers-java:23.5.26` |
| `libs/dexkit_aar/jni/<abi>/libdexkit.so` | DexKit AAR 的 `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}` |

DexKit 2.2.0 AAR：
`https://maven-central.storage-download.googleapis.com/maven2/org/luckypray/dexkit/2.2.0/dexkit-2.2.0.aar`

需要：JDK 8+、Android `build-tools`（aapt2 / zipalign / apksigner）、`r8.jar`、`adb`。
目录约定：仓库同级放一个 `sdk/`（`android.jar`、`r8.jar`、`android-14/{aapt2,zipalign,apksigner}`）。

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1 -NoInstall    # 只构建
powershell -ExecutionPolicy Bypass -File build.ps1 -Device auto   # 构建 + 安装到设备
```

签名：`build.ps1` 默认读 `cmks.jks`；自行生成同名 keystore（密码写在脚本里，可改）。

## 免责声明 / Disclaimer

**中文**

- 本模块仅供**自有设备 / 已明确授权的测试环境**使用（安全研究、兼容性验证）。请勿用于任何未授权设备或未授权用途。
- 本模块与网易云音乐及其关联公司**无任何隶属、合作、赞助或背书关系**；文中出现的产品名、商标、图标归各自权利人所有。
- 模块只在**本机进程内**读取/改写目标 App 自身的网络请求与本地缓存：**不提供服务端能力、不绕过付费或版权内容、不修改服务端数据、不上传或收集任何账号与内容**。日志与台账仅落在本机 App 私有目录（`/sdcard/Android/data/com.netease.cloudmusic/files/`）。
- 软件按"**现状**"提供，**不附带任何明示或暗示的担保**（含可商销性、特定用途适用性）。App 版本升级后个别混淆锚点可能漂移，功能可能部分失效——由此产生的一切后果由使用者自行承担。
- 使用前请自行确认所在地法律法规及目标 App 的用户协议。**如权利人提出要求，会立即下架本仓库与发布物。**
- 请只从本仓库的 **Release** 获取安装包（并核对 sha256）；第三方渠道分发的 APK 与本项目无关。

**English**

- For **own devices / explicitly authorized test environments only** (security research, compatibility verification). Do not use on devices or for purposes you are not authorized for.
- This project is **not affiliated with, endorsed by, or sponsored by** NetEase CloudMusic or its affiliates. All product names and trademarks belong to their respective owners.
- The module only reads/rewrites the target app's own requests and local cache **inside the local process**: no server-side capability, no bypass of paid or copyrighted content, no modification of server data, and no collection or upload of accounts or content. Logs and records stay in the app's private directory on-device.
- Provided **"AS IS"**, **without warranty of any kind**, express or implied. After an app update, some obfuscated anchors may drift and features may partially break; you bear any consequences of use.
- Verify local laws and the app's terms of service before use. **Content will be taken down immediately upon a rights holder's request.**
- Download only from this repository's **Releases** (verify the sha256); APKs from other channels are unrelated to this project.

## 说明

- 目标版本 9.5.96(9005096)。模块内置 DexKit 兜底与自检日志（`/sdcard/Android/data/com.netease.cloudmusic/files/cm_hook.log`）。
- 构建：见上文「构建」段（依赖二进制不随仓库提供）。
