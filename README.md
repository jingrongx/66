# 旅游宝

> 零决策旅行助手：答得短，带数字，能照做。

「旅游宝」的目标不是做"更聪明的行程规划器"，而是做**途中决策的接管器**：系统被动感知你的体力与情境，在你开口之前给出一个可以立刻执行的动作，你只保留否决权。

> 📌 约定：每次提交更新代码时，同步更新本 README 的进度清单与架构说明（CI 会在只改代码不改 README 时给出警告）。

## 当前进度（v0.6.x）

### 已实现

- ✅ AI 接口层：OpenAI 兼容协议，**默认 DeepSeek**（`deepseek-flash` / `deepseek-v4-pro`），支持智谱 GLM、Kimi、通义千问与自定义端点
- ✅ 联网搜索：DeepSeek Search API（`/v1/tools/search`），实时结果注入上下文；主页开关（设置页按人民币计价提示）
- ✅ **Function Calling 工具调用（v0.5）**：模型自动决定调用 `search_poi`（高德 POI 检索）、`get_weather`（Open-Meteo 实时天气）、`get_route`（步行路线）——事实来自工具返回，不靠模型记忆
- ✅ **疲劳积分观测与回测（v0.2 → v0.5 补全）**：被动感知（硬件计步 + GPS 距离/爬升/连续步行）→ 0–100 积分 → 感知日志页可标记真实感受 + **回测分析**（预警覆盖率、平均/最长/最短提前量，用于标定 FatigueEngine 阈值）+ CSV 导出
- ✅ **离线地图（v0.5，v0.6 健壮性重构）**：mapsforge 开源矢量地图，分省下载管理（官方 China 数据源 + 自定义 .map 链接），当前定位标记；下载改临时文件+完成后校验改名，中断/损坏文件自动清理，地图打开失败有明确指引不再崩溃
- ✅ **步行路线（v0.5）**：高德步行路径规划（填 Key 即用）+ **BRouter 应用联动**（AIDL 调用，离线高程路由，需安装 BRouter）——距离/耗时/累计爬升，路线叠加在地图上
- ✅ **感知足迹叠加（v0.6）**：感知服务节流记录轨迹点（≥15 米或 ≥60 秒），离线地图以日出橙细线叠加"今天的足迹"
- ✅ **随手记 + 自动游记（v0.6）**：一句话记录见闻（自动带位置时间）→ AI 合成第一人称游记 → 可朗读、可存进个人记忆
- ✅ **长辈模式（v0.5，v0.6 修复语音）**：语音优先、超大字体、回答自动朗读（TTS）——"宁可少答，不可错答"的老人端落地
- ✅ 语音提问（应用内 SpeechRecognizer，兼容国产 ROM，失败给出直白原因）+ 拍照/相册/文件视觉理解（附件读取失败可见、芯片移入输入卡）
- ✅ 多会话（抽屉：分组/搜索/删除）+ 会话历史上下文
- ✅ 个人信息飞轮 v0：回答收藏为证据，按关键词相关性注入后续提问
- ✅ 规则校验层：≤2 句/含数字，攻略类输出可执行要点；提示人性化（"小提醒：…"），空回复自动兜底不再出空卡片
- ✅ 应用内更新（启动静默检查 + 手动页，国内代理优先多线路）+ 位置上下文（脱敏坐标 + 城市反解）
- ✅ 现代化界面：Material 3 品牌配色（深海青 + 日出橙），外观切换（跟随系统/浅色/深色），参考官方布局重构
- ✅ 提交即自动发版：push 到 main → 构建 release APK（**正式签名**，密钥在 GitHub Secrets）→ GitHub Release，说明自动按 commit 生成

### 未实现（按推演方案排序，后续迭代）

- ⬜ 公共信息飞轮（脱敏贡献公共池：需要用户明示授权 + 轻量后端）
- ⬜ 群体情境层（"汝之蜜糖彼之砒霜"：按同行结构/体力档位加权证据）+ 推荐多样性（探索意愿状态变量）
- ⬜ 足迹热力图与多日轨迹管理（当前只叠加最近轨迹）
- ⬜ 交通时刻表类硬信息的专项数据源接入

## 技术栈

Kotlin · Jetpack Compose (Material 3) · OkHttp · kotlinx.serialization · DataStore · mapsforge（离线地图）· 高德开放平台（POI/路线）· Open-Meteo（天气）· BRouter（AIDL 离线路由）· Gradle Kotlin DSL · GitHub Actions

## 快速开始

```bash
git clone https://github.com/jingrongx/66.git
# 用 Android Studio（Ladybug 以上）打开，或命令行构建：
./gradlew assembleDebug
```

首次启动 → 「设置」→ 选服务商（默认 DeepSeek）→ 填入 API Key → 测试连接 → 保存。可选：填高德 Key 解锁附近搜索与步行路线；去「离线地图」下载所在省份；家里有老人打开「长辈模式」。

DeepSeek API Key 在 [platform.deepseek.com](https://platform.deepseek.com/api_keys) 申请；高德 Key 在 [lbs.amap.com](https://lbs.amap.com) 申请（Web 服务类型）。所有 Key 只保存在本机 DataStore。

## 自动发版与签名

推送到 `main`（或在 Actions 手动 Run workflow）即自动：构建 release APK（正式签名，密钥从 GitHub Secrets 解密，未配置时回退 debug）→ apksigner 验证 → 发布 Release（说明按 commit 生成，自动清理同版本旧构建）。

- 发新版本：修改 `app/build.gradle.kts` 的 `versionName`，tag 自动跟随
- ⚠️ keystore 与密码务必备份（丢失即无法再升级），当前保存位置见仓库外 `lvyoubao-release.jks`

## 架构

```
app/src/main/java/com/lvyoubao/app/
├── data/
│   ├── AiClient.kt            # AI 接口：Function Calling 循环 + 搜索注入 + 视觉 + 纯文本对话
│   ├── AppTools.kt            # 工具执行器：search_poi / get_weather / get_route
│   ├── AmapClient.kt          # 高德：POI 检索 / 步行路线 / 地理编码
│   ├── WeatherClient.kt       # Open-Meteo 实时天气
│   ├── BRouterClient.kt       # BRouter AIDL 联动（离线高程路由）
│   ├── RuleValidator.kt       # 规则校验层（≤2句/含数字），提示人性化
│   ├── SessionStore.kt        # 多会话存储（新建/删除/搜索）
│   ├── MemoryStore.kt         # 个人飞轮：收藏证据 + 相关性检索注入
│   ├── NoteStore.kt           # 随手记存储（JSONL）
│   ├── TrackStore.kt          # 感知轨迹点存储（JSONL，供地图叠加）
│   ├── LocationContext.kt     # 位置上下文（脱敏坐标 + Geocoder 反解城市）
│   ├── UpdateChecker.kt       # 应用内更新（API + atom 双层检查，代理优先）
│   └── observation/           # 疲劳积分观测（引擎 + JSONL 存储 + CSV 导出）
├── sensing/
│   └── FatigueSensingService.kt   # 被动感知前台服务（硬件计步 + LocationManager + 轨迹落点）
├── ui/
│   ├── ask/                   # 对话主界面：消息流 + 合并输入卡 + 快捷开关 + 抽屉
│   ├── elder/                 # 长辈模式：语音优先 + 超大字体 + 自动朗读
│   ├── map/                   # 离线地图：mapsforge 查看器 + 分省下载 + 路线/足迹叠加
│   ├── notes/                 # 随手记 + 自动游记
│   ├── observation/           # 感知日志：积分、指标、真实感受标记、回测分析
│   ├── memory/                # 我的记忆（收藏的证据）
│   ├── settings/              # 设置：AI 服务 / 外观 / 长辈模式 / 关于
│   ├── update/                # 应用更新页
│   └── theme/                 # Material 3 品牌配色（深海青 + 日出橙）
└── MainActivity.kt
```
