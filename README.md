<div align="center">

# 🧭 旅游宝

**零决策 AI 生活全能助手 —— 旅行是看家本领**

答得短 · 带数字 · 能照做 · 全链路免费

[![Release](https://img.shields.io/github/v/release/jingrongx/66?style=flat-square&color=0B7285)](https://github.com/jingrongx/66/releases/latest)
[![Release CI](https://img.shields.io/github/actions/workflow/status/jingrongx/66/release.yml?style=flat-square&label=CI&color=2DA44E)](https://github.com/jingrongx/66/actions/workflows/release.yml)
[![Android](https://img.shields.io/badge/Android-5.1%2B%20%CE%B2%20API%2021-3DDC84?style=flat-square)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin%20%C2%B7%20Jetpack%20Compose-Material%203-7F52FF?style=flat-square)](https://developer.android.com/jetpack/compose)
[![免费](https://img.shields.io/badge/%E5%85%A8%E9%93%BE%E8%B7%AF-%E5%85%8D%E8%B4%B9%E5%BC%80%E6%BA%90%E6%96%B9%E6%A1%88-D9480F?style=flat-square)](#为什么是旅游宝)

[📥 下载最新版](https://github.com/jingrongx/66/releases/latest) · [🚀 快速开始](#-快速开始) · [🏗️ 架构](#️-架构) · [🗺️ 路线图](#️-路线图)

</div>

---

## 为什么是旅游宝

「旅游宝」的能力不设话题边界——生活里的事都能问；在旅行场景（路线、玩法、吃饭、交通、天气）上是看家本领。目标不是做"更聪明的行程规划器"，而是做**途中决策的接管器**：系统被动感知你的体力与情境，在你开口之前给出一个可以立刻执行的动作，**你只保留否决权**。

| 设计原则 | 一句话解释 |
|---|---|
| 🚫 零决策 | 不给你一堆选项让你挑，只给一个能立刻照做的动作 |
| 🔢 带数字 | 时间、距离、价格、班次——没有数字的建议不算建议 |
| 🗣️ 语音优先 | 按住说话、松手即问、回答自动朗读，路上不用打字 |
| 💰 免费优先 | 大模型、语音识别、朗读、离线地图全部有免费开源方案 |

## ✨ 特性一览

| | 特性 | 说明 |
|---|---|---|
| 📞 | **语音通话** | 全屏通话界面：按住说话松手即问、回答自动朗读、可插话；通话里也能拍照发图，文字稿=播报全文 |
| 🔍 | **拍照提问** | 对准眼前的事物拍一张直接问，图文一起发出 |
| 🧭 | **会话地图与导航** | 涉及地点的回答直接配地图卡：真实路线/多方案/分步指引，一键开始导航或跳高德 |
| 🏠 | **常用地址（家）** | 设置一次，问「回家的路」直接导航回家，不再反问 |
| 📡 | **疲劳感知** | 被动感知计步/步行/爬升 → 0-100 体力积分 → 在你开口之前提醒该歇了，可回测标定 |
| 🗺️ | **离线地图** | mapsforge 开源矢量图，分省下载、中文地名、足迹叠加，没网也认路；8 段并行下载+断点续传，数据有新版自动提醒 |
| 🎙️ | **方言口音识别** | TeleSpeech-ASR 免费模型覆盖 23 种方言口音，越用越准 |
| ⏱️ | **语音自动发送** | 松手识别（含智能纠错）完自动发送，延迟可选（关/立即/1/3/5 秒，默认关）；点输入框即取消 |
| 🔊 | **三级朗读引擎·秒播** | Edge 流式合成边收边播（首声≈1秒）→ 硅基流动 CosyVoice2 → 系统 TTS，长回答不再干等 |
| 📊 | **表格/图表卡** | 回答里的 Markdown 表格自动成表卡（全屏/横屏、存 CSV/图片）；流程图/思维导图（mermaid）自动成图卡，全屏双指缩放、存图片 |
| 🧠 | **记忆画像** | 会话自动总结长期事实（住址、口味、习惯称呼），注入上下文越聊越懂你 |
| 📤 | **卡片分享** | 回答一键生成竖版卡片图（表格/图表原样收录），分享到微信或存相册；安卓直达微信选联系人，鸿蒙卓易通走系统分享面板 |
| ✍️ | **随手记·自动游记** | 一句话记录见闻，AI 合成第一人称游记，可朗读可收藏 |
| 🈯 | **热词纠正** | 从聊天/随手记/记忆里挖高频词建本地词库，同音自动纠正 |
| ⚡ | **应用内更新** | 启动静默检查 + 手动检查，多线路代理优先 |

## 🧭 两种界面

| | 普通模式 | 大字模式 |
|---|---|---|
| 定位 | 全功能主界面 | 家里老人的默认界面 |
| 字号 | 标准 | 超大字（按钮 56dp/20sp 起） |
| 交互 | 打字 + 语音 + 快捷开关 | 语音优先：按住说话、说完松手就发 |
| 进入 | 未开大字模式时的默认 | 设置里打开后**启动即进**，退出/返回键=退出应用 |

> 模式切换只认设置里的「大字模式」开关；回答风格（简洁/中等/详尽，默认简洁）同样在设置里调。

## 📥 下载安装

- **手机**：到 [Releases](https://github.com/jingrongx/66/releases/latest) 下载 APK 安装；应用内会自动提示新版本
- **车机**（Android 5.1 也支持）：手机「设置 → 扫码推送」，同一 Wi-Fi/热点下把 AI Key、常用地址直推车机，无需在车机上打字

## 🚀 快速开始

```bash
git clone https://github.com/jingrongx/66.git
# 用 Android Studio（Ladybug 以上）打开，或命令行构建：
./gradlew assembleDebug
```

首次启动配置：

1. 「设置」→ 选服务商（默认 DeepSeek）→ 填入 API Key → 测试连接 → 保存
2. 可选：填高德 Key 解锁附近搜索与步行路线；去「离线地图」下载所在省份；填「常用地址（家）」解锁一句话回家；家里有老人打开「大字模式」

- DeepSeek API Key 在 [platform.deepseek.com](https://platform.deepseek.com/api_keys) 申请
- 高德 Key 在 [lbs.amap.com](https://lbs.amap.com) 申请（**Web 服务**类型）
- 所有 Key 只保存在本机 DataStore

## 🏗️ 架构

```
app/src/main/java/com/lvyoubao/app/
├── data/
│   ├── AiClient.kt            # AI 接口：Function Calling 循环 + 搜索注入 + 视觉 + 纯文本对话
│   ├── AppTools.kt            # 工具执行器：search_poi / get_weather / get_route
│   ├── AmapClient.kt          # 高德：POI 检索 / 步行路线 / 地理编码（GCJ-02↔WGS-84 边界互转）
│   ├── WeatherClient.kt       # Open-Meteo 实时天气
│   ├── BRouterClient.kt       # BRouter AIDL 联动（离线高程路由）
│   ├── RuleValidator.kt       # 规则校验层（≤2句/含数字），提示人性化且客观
│   ├── SessionStore.kt        # 多会话存储（新建/删除/搜索）
│   ├── MemoryStore.kt         # 记忆画像：会话自动总结 + 相关性注入 + 热词语料
│   ├── NoteStore.kt           # 随手记存储（JSONL）
│   ├── TrackStore.kt          # 感知轨迹点存储（JSONL，供地图叠加）
│   ├── GpxUtils.kt            # 标准 GPX 1.1 路书解析与足迹导出（户外轨迹循迹）
│   ├── LocationContext.kt     # 位置上下文（脱敏坐标 + Geocoder 反解城市）
│   ├── UpdateChecker.kt       # 应用内更新（API + atom 双层检查，代理优先）
│   ├── ConfigSync.kt          # 扫码同步配置：载荷编解码（局域网推送 + 配置直带两种二维码）
│   ├── ConfigSyncServer.kt    # 扫码同步接收端：一次性本地 HTTP 服务（ServerSocket + 口令校验）
│   └── observation/           # 疲劳积分观测（引擎 + JSONL 存储 + CSV 导出）
├── sensing/
│   └── FatigueSensingService.kt   # 被动感知前台服务（硬件计步 + LocationManager + 轨迹落点）
├── ui/
│   ├── ask/                   # 对话主界面：消息流 + 合并输入卡（按住说话）+ 快捷开关 + 抽屉
│   ├── common/                # EdgeTts（云端朗读）+ TtsPlayer + SpeechInput（系统识别/大模型转文字，方言口音模型）
│   ├── largetext/             # 大字模式：会话流 + 底部常驻操作区 + 按住说话 + 拍照提问 + 对话历史 + 语音通话 + 设置/更新入口
│   ├── map/                   # 离线地图：mapsforge 查看器 + 中文省区 + 路线/足迹叠加
│   ├── notes/                 # 随手记 + 自动游记
│   ├── observation/           # 感知日志：积分、指标、真实感受标记、回测分析
│   ├── memory/                # 我的记忆（收藏的证据 + 画像）
│   ├── settings/              # 设置：AI 服务 / 扫码同步 / 外观 / 回答风格 / 大字模式 / 常用地址 / 关于
│   ├── sync/                  # 扫码同步：二维码渲染 + 扫码界面（Camera1 + ZXing，兼容 API 21+）
│   ├── update/                # 应用更新页
│   └── theme/                 # Material 3 品牌配色（深海青 + 日出橙）+ 全局尺寸体系
└── MainActivity.kt            # 按设置决定启动进大字模式还是普通模式
```

## 🗺️ 路线图

- ⬜ **视频通话**（AI 真人般实时视频对话）：真正的全双工视频对话需要实时全模态大模型，按量计费与"能免费就免费"冲突；当前免费替代 = **语音通话 + 拍照提问**，已覆盖"边看边问"的主要场景
- ⬜ 公共信息飞轮（脱敏贡献公共池：需要用户明示授权 + 轻量后端）
- ⬜ 群体情境层（按同行结构/体力档位加权证据）+ 推荐多样性（探索意愿状态变量）
- ⬜ 足迹热力图与多日轨迹管理（当前只叠加最近轨迹）
- ⬜ 完全离线的端侧语音模型（端侧需引入 sherpa-onnx 类原生库 + 模型下载管理）
- ⬜ 交通时刻表类硬信息的专项数据源接入

## 📜 更新日志

**v0.8.66** · 新增「语音后自动发送」+ 大字模式顶部操作带——①语音后自动发送（设置 → 语音输入，默认关）：按住说话松手、识别（含 AI 智能纠错）完成后，按所选延迟（关/立即/1/3/5 秒）自动把这条发出去；点一下输入框、再次按住说话、手动编辑或清空都会取消倒计时；输入框上方实时显示「N 秒后自动发送」；连说几段时每段松手都会重新调度，最后一段说完才开始数；普通/大字模式生效，语音通话松手即问不受影响；②大字模式顶部改成浅青灰操作带（圆角下缘+投影），顶部导航（设置/新对话/历史对话/通话）改白底深字——浅蓝只属于用户气泡，顶部操作区与会话区一眼分开，与底部操作带上下呼应。

**v0.8.64** · 大字模式视觉降噪与分区：回答区/操作区颜色分层，告别「全屏都是按钮」——①色重分层体系：深青实心只留给主操作（按住说话/发送，退出保留红色警示），顶部导航（新对话/历史对话/通话/设置）降浅青底深字，回答卡操作行（朗读/收藏/分享）与用户气泡（编辑/重发）降浅青灰底；②底部操作区改浅青灰底，与浅青白内容区+白卡片明确分层，一眼分清「上面是回答、下面是操作」；③草稿输入框改白底，在浅青灰操作区上像一张纸更清楚。

**v0.8.63** · 大字模式回答卡按钮行改等宽三分（朗读/收藏/分享完整可见，不再被挤出屏幕）；分享到微信识别鸿蒙卓易通容器（Build 属性被伪装，靠安装来源与容器内商店包名检测，检测到直接走系统分享面板，不再弹「卓易通内暂不支持」）；保存到相册的成功/失败改大字提醒卡（约 2.6 秒自动消失、点击关闭，普通/大字模式共用）。

**v0.8.62** · 分享卡片图升级：正文按块渲染，表格手绘整表（单元格自动换行不截断，≥4 列宽表自动转横版）、mermaid 图表离线渲染整页嵌入；鸿蒙卓易通分享自动改走系统分享面板；大字模式补齐收藏与分享。

各版本的完整更新说明见 [Releases](https://github.com/jingrongx/66/releases)。

## 🔐 自动发版

推送到 `main`（或在 Actions 手动 Run workflow）即自动：构建 release APK（正式签名，密钥存于 GitHub Secrets）→ apksigner 验证 → 发布 Release（说明按 commit 自动生成，自动清理同版本旧构建）。

发新版本只需修改 `app/build.gradle.kts` 的 `versionName`，tag 自动跟随。

> ⚠️ 签名密钥务必备份——丢失后将无法再以同一签名升级应用。

## 📌 仓库约定

提交更新代码时，同步更新本 README 的特性说明与架构图（CI 会提醒只改代码不改 README 的提交）；更新历史统一收进 Releases。

## 技术栈

Kotlin · Jetpack Compose (Material 3) · OkHttp · kotlinx.serialization · DataStore · mapsforge（离线地图）· 高德开放平台（POI/路线）· Open-Meteo（天气）· BRouter（AIDL 离线路由）· TeleSpeech-ASR / SenseVoice（方言口音语音转写，免费模型）· Edge TTS（朗读）· Gradle Kotlin DSL · GitHub Actions
