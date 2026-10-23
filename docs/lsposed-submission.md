# LSPosed 模块仓库提交指南

本指南用于将「音乐外屏增强」提交到 [Xposed Module Repository](https://modules.lsposed.org/)。规则核对日期：2026-10-23。本文和配套文件是本地提交材料，不表示已完成域名验证、仓库申请、审核或发布。

## 可直接填写的信息

| 字段 | 内容 |
| --- | --- |
| 模块名称 / 仓库 Description | 音乐外屏增强 |
| Application ID / 分发仓库名 | `vip.chener.musicenhance` |
| 维护者 GitHub 账号 | `daixu0502`（依据当前源码仓库地址；使用其他账号提交时同步修改验证记录） |
| 源码仓库 | https://github.com/daixu0502/MusicEnhance |
| 支持链接 / 仓库 Homepage | https://github.com/daixu0502/MusicEnhance/issues |
| 申请 Issue 标题 | `[submission] vip.chener.musicenhance` |
| 当前构建版本 | `versionName = 3.2.0`、`versionCode = 30200`，以 `app/build.gradle.kts` 为准 |
| 分发仓库 Release 标题 | `3.2.0` |
| 分发仓库 Release 标签 | `30200-3.2.0` |
| APK 附件名称建议 | `MusicEnhance-v3.2.0.apk`，必须为实际签名的 Release APK |
| 最低系统 / 架构 | Android 14（API 34）/ `arm64-v8a` |
| 框架要求 | 支持现代 libxposed API 102 的 LSPosed 环境 |

版本值仅记录当前源码，不代表该包名版本已经公开发布。发布前读取实际 APK 的 Application ID、versionName 和 versionCode，再填写对应标签；后续版本应同时递增版本号和 versionCode。

### 本次本地核验结果

- 已运行本文的测试、Release lint 和构建命令：584 项单元测试通过，0 失败、0 错误、0 跳过。
- Release lint 为 0 错误、5 项警告：4 项依赖版本更新提示及 1 项 ChromeOS x86_64 支持提示；本次未调整依赖或目标架构。
- 已从实际 APK 核对包名 `vip.chener.musicenhance`、版本 `30200 / 3.2.0`，并检查压缩后的 DEX 保留了新的 Xposed 入口且没有旧包名引用。
- 当前产物为 `app/build/outputs/apk/release/app-release-unsigned.apk`，尚未签名，不能直接发布安装；未执行安装、真机复测、DNS 配置、申请或上传。

## 1. 完成域名验证

[官方申请说明](https://github.com/Xposed-Modules-Repo/submission#application-id-requirement)要求反向域名形式的 Application ID 使用自己拥有的域名，并在根域名设置 TXT 验证记录。`vip.chener.musicenhance` 对应根域名 `chener.vip`。

在该域名的 DNS 管理面板添加：

| DNS 字段 | 值 |
| --- | --- |
| 类型 | `TXT` |
| 主机记录 | `@`（根域名 `chener.vip`） |
| 记录值 | `lsposed-modules-repo-verification=daixu0502` |

确认记录已经生效后再提交。此记录需要域名管理权限，本次未修改 DNS，也未核验域名所有权。实际提交账号如果不同，必须把记录值改成该账号。若没有该域名所有权，不应以其申请；官方提供 `io.github.<账号>` 前缀作为替代方案，采用替代包名需另外修改项目。

## 2. 提交申请

打开 [官方申请页面](https://modules.lsposed.org/submission/)，选择提交模块，填写包名及说明；也可以在 [submission 仓库](https://github.com/Xposed-Modules-Repo/submission/issues)新建 Issue。

标题：

```text
[submission] vip.chener.musicenhance
```

正文可以直接复制：

```markdown
## Module information

- Name: 音乐外屏增强 / MusicEnhance
- Application ID: vip.chener.musicenhance
- Source: https://github.com/daixu0502/MusicEnhance
- Support: https://github.com/daixu0502/MusicEnhance/issues

MusicEnhance provides an outer-screen music player for Xiaomi MIX Flip and MIX Flip 2 on HyperOS. It adds album artwork, synchronized lyrics, playback controls, camera-aware layouts and spectrum animations while playback remains in the supported music apps.

Requires Android 14 or later, arm64-v8a, and an LSPosed environment supporting modern libxposed API 102. Supported apps include QQ Music, Apple Music, Kuwo Music, Kugou Music, Kugou Lite, Salt Player, LX-X Music and Spotify. Availability depends on host versions; LX-X Music and Spotify still require on-device verification.

I would like to submit this module and maintain its distribution repository under Xposed-Modules-Repo.
```

如果需要在正文补充域名验证结果，请在实际确认 TXT 生效后填写，勿直接声明尚未完成的验证。收到邀请后接受分发仓库维护权限；最终仓库地址和审批结果以官方回复为准。

## 3. 准备分发仓库

官方要求分发仓库以模块包名命名，并填写非空 Description。预期仓库名为 `Xposed-Modules-Repo/vip.chener.musicenhance`；此处不表示该仓库已经创建。

本项目继续在现有源码仓库开发。根据[官方仓库内容说明](https://github.com/Xposed-Modules-Repo/submission#repository-content)，分发仓库不需要上传源码。向分发仓库默认分支复制以下文件：

| 本项目文件 | 分发仓库位置 | 用途 |
| --- | --- | --- |
| [`../SUMMARY`](../SUMMARY) | `/SUMMARY` | 模块列表摘要，文件名没有扩展名 |
| [`lsposed/README.md`](lsposed/README.md) | `/README.md` | 面向模块用户的完整说明 |

将分发仓库的 Description 设置为「音乐外屏增强」，Homepage 设置为上述支持链接。`release-notes.md` 是创建 Release 时的正文模板，无需作为模块元数据上传；本指南也无需复制。

## 4. 构建、核验并创建 Release

在源码仓库配置 README 中列出的签名环境变量后，运行：

```powershell
.\gradlew.bat --no-configuration-cache :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

检查实际测试结果及 lint 报告。使用 Android SDK 的 `apksigner verify --verbose --print-certs` 检查 `app/build/outputs/apk/release/app-release.apk` 的签名证书，并用 `aapt2 dump badging` 核对包名和版本。没有签名配置时生成的 `app-release-unsigned.apk` 不能作为可安装的发布附件。不要上传 Debug APK、签名密钥或密码。

在分发仓库创建 Release，设置标题 `3.2.0`、标签 `30200-3.2.0`，正文参考 [`lsposed/release-notes.md`](lsposed/release-notes.md)，同时附上经过核验的签名 APK。可额外提供 APK 的 SHA-256 文件。新版本按实际 APK 值使用 `<versionCode>-<versionName>`；Beta 版本同时勾选 Pre-release。

源码仓库的现有 Actions 仍使用 `v3.2.0` 形式的标签，且只向源码仓库发布；不会自动同步至官方分发仓库。两处标签规则不同，不要将源码发布标签直接用作模块仓库标签，也不要覆盖已经公开的版本或旧包名附件。

按[官方发布说明](https://github.com/Xposed-Modules-Repo/submission#important-notes)，创建或更新 Release 时应同时包含有效 APK；仅替换附件不会触发机器人更新。填写完整资料并发布有效 Release 后，索引更新通常需要 5–60 分钟。

## 5. 发布前人工确认

- TXT 记录已生效，申请账号已接受仓库邀请。
- 分发仓库名称、Description、Homepage、`SUMMARY` 和 `README.md` 已填写。
- APK 的包名为 `vip.chener.musicenhance`，版本与 Release 标签一致，签名证书符合后续升级计划。
- 已在目标设备停用旧模块，启用新包名模块并重新配置；重启后确认系统框架、System UI 和宿主加载的是新模块。
- 已验证外屏冷启动、返回、切歌、歌词定位、旋转和内外屏切换；未验证的应用或路径保留明确说明。

包名迁移会安装成独立应用，旧包名配置不会自动迁移；迁移操作见分发版 README。
