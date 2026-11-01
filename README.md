# 旅游宝

> 零决策旅行助手：答得短，带数字，能照做。

「旅游宝」的目标不是做"更聪明的行程规划器"，而是做**途中决策的接管器**：系统被动感知你的体力与情境，在你开口之前给出一个可以立刻执行的动作，你只保留否决权。

## 当前进度（v0.3.x）

- ✅ AI 接口层：OpenAI 兼容协议，**默认 DeepSeek**（当前模型 `deepseek-flash` / `deepseek-v4-pro`，已按官方文档核实），支持智谱 GLM、Kimi、通义千问与自定义 OpenAI 兼容端点
- ✅ 联网搜索（v0.3）：接入 DeepSeek Search API（`/v1/tools/search`），提问前实时检索并把结果注入上下文——实时信息能力落地；主页开关"深度思考 / 联网搜索"
- ✅ 视觉输入（v0.3）：拍照 / 相册（压缩为 base64 data URL，deepseek-flash 支持视觉）；文本类文件直接读内容
- ✅ 会话系统（v0.3）：多会话、新建/删除、按标题和内容搜索、当前会话跨启动恢复；回答按"快问快答 ≤2 句 / 攻略类 3–5 条要点"分级输出
- ✅ 位置上下文（v0.3）：提问自动附带当前时间与脱敏位置（城市/区域 + 约 1km 精度坐标），感知服务的实时定位优先
- ✅ 个人信息飞轮 v0（v0.3）：回答可"收藏"为证据，本地记忆库按关键词相关性注入后续提问；公共飞轮待用户明示授权后接入
- ✅ 规则校验层：AI 输出必须满足"≤2 句、含数字"，违例标记降级——规则层对模型有一票否决权
- ✅ 现代化界面：Jetpack Compose + Material 3，固定品牌配色（深海青 + 日出橙，弃用动态取色以保证对比度）
- ✅ 应用内更新：启动静默检查 + 设置里的「应用更新」页手动检查/下载/安装；国内代理优先且多线路自动换线（API 直连 → 镜像 API → atom 兜底）
- ✅ 疲劳积分观测版（v0.2.0）：被动感知前台服务（硬件计步 + GPS 距离/爬升/连续步行），每分钟计算 0–100 积分，样本/事件本地记录，可标记真实感受并导出 CSV 回测——只记录，不干预
- ✅ 提交代码即自动发版：push 到 main 触发 GitHub Actions 构建 APK 并发布 Release
- ⬜ 疲劳积分回测与标定（观测数据吻合度分析 → 调权重与阈值）
- ⬜ mapsforge 离线地图 + BRouter 高程路由
- ⬜ Function Calling 接入高德 POI / 公共客流接口
- ⬜ 老人模式（语音 + 震动，零屏幕交互）与家人协同

## 技术栈

Kotlin · Jetpack Compose (Material 3) · OkHttp · kotlinx.serialization · DataStore · Gradle Kotlin DSL · GitHub Actions

## 快速开始

```bash
git clone https://github.com/jingrongx/66.git
# 用 Android Studio（Ladybug 以上）打开，或命令行构建：
./gradlew assembleDebug
```

首次启动 → 右上角「设置」→ 选服务商（默认 DeepSeek）→ 填入 API Key → 「测试连接」验证 → 保存。

DeepSeek API Key 在 [platform.deepseek.com](https://platform.deepseek.com/api_keys) 申请。Key 只保存在本机 DataStore，不上传。

## 自动发版

推送到 `main` 分支（或在 Actions 页面手动 Run workflow）即自动：构建 release APK → 创建 GitHub Release（tag 形如 `v0.1.0-b42`）→ 附件可直接下载安装。

- 发版 APK 当前用 debug 签名（保证可安装）；接入正式签名后替换 `app/build.gradle.kts` 中的 signingConfig
- 发新版本时修改 `app/build.gradle.kts` 里的 `versionName`，tag 会自动跟着变

## 应用内更新

启动时静默检查 GitHub Releases（有新版本才在主页出「发现新版本」单卡，失败不打扰）；「设置 → 应用更新」里可手动检查、查看更新日志、带进度下载并调起安装（首次需允许「安装未知应用」）。

- 检查/下载国内代理优先：`gh-proxy.com` → `ghfast.top` → 直连，任一线路成功即用，代理列表在 `data/UpdateChecker.kt`
- **前提：仓库需为公开**（匿名可读 Release）；私有仓库时应用内检查会提示"找不到更新信息"

## 架构

```
app/src/main/java/com/lvyoubao/app/
├── data/
│   ├── AiClient.kt            # 统一 AI 接口（OpenAI 兼容 + DeepSeek 搜索 API + 视觉）
│   ├── RuleValidator.kt       # 规则校验层（≤2句/含数字），对模型输出一票否决
│   ├── SessionStore.kt        # 多会话存储（新建/删除/搜索）
│   ├── MemoryStore.kt         # 个人飞轮：收藏证据 + 相关性检索注入
│   ├── LocationContext.kt     # 位置上下文（脱敏坐标 + Geocoder 反解城市）
│   ├── SettingsRepository.kt  # 服务商/Key/模型/开关配置（DataStore，本地保存）
│   ├── UpdateChecker.kt       # 应用内更新（API + atom 双层检查，代理优先）
│   └── observation/           # 疲劳积分观测版（引擎 + 本地存储）
├── sensing/
│   └── FatigueSensingService.kt   # 被动感知前台服务（硬件计步 + LocationManager）
├── ui/
│   ├── ask/                   # 对话主界面：消息流 + 单卡动作 + 快捷开关 + 附件
│   ├── sessions/              # 会话列表（搜索/新建/删除）
│   ├── memory/                # 我的记忆（收藏的证据）
│   ├── observation/           # 感知日志页：积分、指标、真实感受标记、事件时间线
│   ├── settings/              # 设置层
│   ├── update/                # 应用更新页
│   └── theme/                 # Material 3 品牌配色（固定，保证对比度）
└── MainActivity.kt
```

后续按推演方案扩展：`trust-engine/`（可信度路由）、`decision-engine/`（降级动作生成）、`data-source/`（高德/客流适配器）。
