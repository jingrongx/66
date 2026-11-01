# 旅游宝

> 零决策旅行助手：答得短，带数字，能照做。

「旅游宝」的目标不是做"更聪明的行程规划器"，而是做**途中决策的接管器**：系统被动感知你的体力与情境，在你开口之前给出一个可以立刻执行的动作，你只保留否决权。完整的产品推演过程见 [docs/旅游宝-DeepSeek对话全文.md](docs/旅游宝-DeepSeek对话全文.md)（15 轮对话，从痛点到技术方案）。

## 当前进度（v0.1.x）

- ✅ AI 接口层：OpenAI 兼容协议，**默认 DeepSeek**（当前模型 `deepseek-flash` / `deepseek-v4-pro`，已按官方文档核实），支持智谱 GLM、Kimi、通义千问与自定义 OpenAI 兼容端点
- ✅ 规则校验层：AI 输出必须满足"≤2 句、含数字"，违例标记降级——规则层对模型有一票否决权
- ✅ 现代化界面：Jetpack Compose + Material 3，动态取色，单卡交互（回答 + 一个动作 + ［就这么走］）
- ✅ 应用内更新：启动静默检查 + 设置里的「应用更新」页手动检查/下载/安装；下载国内代理优先（gh-proxy.com → ghfast.top → 直连，自动换线）
- ✅ 提交代码即自动发版：push 到 main 触发 GitHub Actions 构建 APK 并发布 Release
- ⬜ 疲劳积分（被动感知 → 三级降级）——Phase 0 核心，先做"只记录不干预"的观测版
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
│   ├── AiClient.kt            # 统一 AI 接口（OpenAI 兼容，默认 DeepSeek；JSON 输出）
│   ├── RuleValidator.kt       # 规则校验层（≤2句/含数字），对模型输出一票否决
│   ├── SettingsRepository.kt  # 服务商/Key/模型配置（DataStore，本地保存）
│   └── ProviderPreset.kt      # DeepSeek（默认）/ 智谱 / Kimi / 通义 / 自定义
├── ui/
│   ├── ask/                   # 问答层：一句话回答 + 单卡动作
│   ├── settings/              # 设置层
│   └── theme/                 # Material 3 主题（动态取色 + 品牌 fallback）
└── MainActivity.kt
```

后续按推演方案扩展：`sensing/`（被动感知）、`trust-engine/`（可信度路由）、`decision-engine/`（疲劳积分 + 降级）、`data-source/`（高德/客流适配器）。
