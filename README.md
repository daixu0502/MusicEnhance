# 音乐外屏增强 · MusicEnhance

为 **Xiaomi MIX Flip / MIX Flip 2** 打造的外屏音乐播放器增强模块，基于 **libxposed API 102**。

在外屏使用 QQ 音乐时，提供全屏专辑背景、低频律动、滚动歌词和围绕摄像头布局的播放控件。设置界面采用 Miuix 风格。

[下载安装](https://github.com/daixu0502/MusicEnhance/releases) · [反馈问题](https://github.com/daixu0502/MusicEnhance/issues) · [构建与发布](#本地构建) · [开发与扩展](#开发与扩展)

## 支持范围

| 项目 | 当前适配 |
| --- | --- |
| 设备 | Xiaomi MIX Flip（`ruyi`）、Xiaomi MIX Flip 2（`bixi`） |
| 系统 | 小米 HyperOS，安装最低要求为 Android 14（API 34） |
| 框架 | 支持现代 **libxposed API 102** 的 LSPosed 环境 |
| 音乐应用 | **QQ 音乐 20.8.5.8**（`com.tencent.qqmusic`） |
| 使用场景 | 外屏播放页；内屏保留 QQ 音乐原界面 |

模块依赖 HyperOS 外屏接口和 QQ 音乐私有接口。系统或 QQ 音乐更新后可能需要重新适配；仅满足 Android 版本要求不代表所有设备都可使用。目前未适配网易云音乐或其他音乐播放器。

## 功能

### 外屏播放器

- **页面显示**：在页面创建、恢复及窗口首帧时检查增强界面，保持其位于原生内容之上；控件在首次测量时完成布局，页面退出时取消未执行的加载。
- **全屏封面**：专辑图铺满背景，从屏幕中部向歌曲信息区域逐渐增强模糊。
- **摄像头避让**：黑色摄像头区域与控件根据外屏方向布局；正反切换使用整页 180° 旋转动画。
- **低频律动**：以低音、鼓点等低频信号驱动摄像头之间的频谱，颜色取自封面主色；暂停后平滑回落。
- **外屏常亮**：设置页提供「播放器外屏常亮」开关，默认关闭。开启后仅在外屏播放器处于前台时防止自动息屏锁屏，歌词页同样有效；退出、切回内屏或窗口失去焦点后释放，仍可手动锁屏。
- **播放控制**：播放/暂停、上一首、下一首、拖动进度，以及当前歌曲的喜欢状态。
- **直接切换循环模式**：顺序播放 → 列表循环 → 单曲循环 → 随机播放，无需弹出菜单。
- **返回首页**：退出增强播放器后恢复 QQ 音乐首页的原生半屏布局和外屏小部件。

### 滚动歌词

点击播放器中不属于按钮、进度条等控件的空白区域，即可切换到歌词页。

- 专辑背景切换为更深的整屏模糊，歌曲信息移到返回键旁边。
- 黑色侧栏平滑延伸，容纳喜欢、播放/暂停和专辑缩略图。
- 歌词自动跟随播放，通常将上一句、当前句放在前两行；可见歌词逐行错峰上移，带轻微弹簧回弹，快速跳转时从当前位置接续动画。触摸立即停止自动推进，手动滚动不被动画抢占。
- 弹动与模糊在同一绘制帧合并更新，文字层预留模糊空间，保留原有行距和文字点击范围。
- 当前句保持清晰，距离当前句越远，上下两侧的歌词模糊越深。
- 手动滚动时平滑取消模糊，并显示中央选中句的开始时间；停止操作约 4 秒后恢复跟随。
- 点击歌词文字可跳转到该句并播放；点击空白区域或专辑缩略图返回封面页。
- 没有可用逐句歌词时显示空态，不生成示例歌词。

### 封面加载与预缓存

优先获取高清专辑图；大图不可用时尝试较小尺寸，专辑图不可用时再尝试歌手图，并跳过已识别的 QQ 通用占位封面。

首次进入播放器及切换歌曲后，会根据 QQ 的**实际播放队列，预缓存前 3 首和后 3 首**的封面：

- 从最近的上一首、下一首开始加载，切歌后更新缓存范围。
- 重叠歌曲复用已有缓存，短列表自动去重；列表循环可跨越首尾，随机播放使用 QQ 已生成的随机队列。
- 当前歌曲与预加载使用独立后台任务；切歌或退出后取消过期任务。
- 文件缓存最多 **64 MiB**，图片内存缓存最多 **24 MiB**，不长期保留 6 张解码大图。
- 切歌等待新图时，背景最多保留上一帧约 1.5 秒，减少灰色空白过渡。

封面预加载会使用网络。文件保存在 **QQ 音乐的应用缓存目录**；被系统或用户清理后，会在后续使用时重新加载。歌曲缺图、网络速度或连续快速切歌仍可能造成等待。

## 安装与启用

1. 从 [Releases](https://github.com/daixu0502/MusicEnhance/releases) 下载并安装 APK；希望尝试测试版本时，可选择标记为 **Pre-release** 的版本。
2. 在 LSPosed 中启用「音乐外屏增强」，确认以下作用域：

   | 作用域 | 用途 |
   | --- | --- |
   | 系统框架（`system`） | HyperOS 外屏兼容与布局策略 |
   | QQ 音乐（`com.tencent.qqmusic`） | 播放器、歌词、封面和播放控制 |
   | 系统界面（`com.android.systemui`） | 正反方向的系统旋转动画 |

3. 打开模块设置，确认激活状态，并打开「Hook QQ 音乐」；按需开启「播放器外屏常亮」。
4. **首次启用或更新模块后，重启手机。**
5. 从外屏打开 QQ 音乐，进入播放页使用增强界面。

仅切换模块内的 Hook 开关时，强制停止并重新打开 QQ 音乐即可。模块设置页用于配置，不提供独立播放器或预览入口。

## 常见问题

### 启用后界面没有变化

检查设备、QQ 音乐版本和 API 102 框架是否符合上述要求，确认模块作用域和 Hook 开关已启用。首次安装后需重启手机，并在**外屏播放页**查看；内屏和 QQ 音乐首页不会显示增强播放器。

### 为什么有些歌曲显示歌手图，或封面仍需等待？

部分歌曲没有有效专辑图，模块会使用歌手图兜底。预缓存仅覆盖当前队列前后各 3 首，无法覆盖尚未生成的队列、远距离跳转或未完成下载的图片。

### 为什么喜欢按钮暂时不可用？

模块需要先读取 QQ 音乐当前歌曲的收藏状态；数据尚未就绪时会等待，避免把未知状态误显示成「未喜欢」。收藏操作仍由 QQ 音乐处理。

### 安装时提示签名不一致

新旧 APK 需要使用相同证书才能覆盖安装。自行编译的 Debug 包通常与 Release 包签名不同；更换签名前请记录模块设置，必要时卸载旧模块，再安装并重新启用。

### 如何反馈问题？

请在 [Issues](https://github.com/daixu0502/MusicEnhance/issues) 提供设备型号、Android/HyperOS 版本、QQ 音乐版本、模块版本，以及复现步骤。摄像头错位问题请注明进入播放器前后的屏幕方向、是否经历内外屏切换，并附截图或录像。

可同时附上复现后的 LSPosed 日志，模块日志标签为 `MusicEnhance`。封面或歌词问题请补充歌曲名、歌手及具体版本（例如现场版）。分享日志前请检查其中是否包含个人信息。

## 本地构建

需要 **JDK 17** 和 **Android SDK Platform 37.0**（SDK 包名 `platforms;android-37.0`）。使用项目自带的 Gradle Wrapper，无需另外安装 Gradle；首次构建需要联网下载依赖。

将项目导入 Android Studio 并配置 SDK 路径，或通过本地 `local.properties` 设置 `sdk.dir`。当前构建配置可查看 [app/build.gradle.kts](app/build.gradle.kts) 和 [版本目录](gradle/libs.versions.toml)。

以下命令在项目根目录运行，以 Windows PowerShell 为例：

```powershell
# 编译 Debug
.\gradlew.bat :app:assembleDebug

# 单元测试与 Release 静态检查
.\gradlew.bat --no-configuration-cache :app:testDebugUnitTest :app:lintRelease
```

macOS / Linux 使用 `./gradlew` 替换 `.\gradlew.bat`。

### 签名 Release

在构建进程中设置以下环境变量，证书与密码不要提交到仓库：

| 环境变量 | 内容 |
| --- | --- |
| `MUSICENHANCE_KEYSTORE` | 签名证书的绝对路径 |
| `MUSICENHANCE_KEY_ALIAS` | 密钥别名 |
| `MUSICENHANCE_STORE_PASSWORD` | 证书库密码 |
| `MUSICENHANCE_KEY_PASSWORD` | 密钥密码；省略时使用证书库密码 |

```powershell
.\gradlew.bat --no-configuration-cache --no-daemon :app:assembleRelease
```

Release 已启用 **R8 代码压缩、混淆和资源裁剪**，并保留 LSPosed 模块入口。未配置完整签名环境变量时，生成未签名包。

| 构建方式 | 默认 APK 路径 |
| --- | --- |
| Debug | `app/build/outputs/apk/debug/app-debug.apk` |
| 已签名 Release | `app/build/outputs/apk/release/app-release.apk` |
| 未签名 Release | `app/build/outputs/apk/release/app-release-unsigned.apk` |

通过 Android Studio 的「Generate Signed Bundle / APK」生成时，输出位置以向导选择的目录为准，也可能位于 `app/release/`。

### GitHub 自动发布

仓库已提供 [Release 工作流](.github/workflows/release.yml)，支持推送到 `main`、推送匹配的 `v*` 标签或在 Actions 手动运行。

| 应用版本示例 | 发布类型 |
| --- | --- |
| `2.1`、`2.1.1` | 正式 Release |
| `2.1-beta`、`2.1-beta.1` | Pre-release |

- 版本从 Gradle 配置读取；发布新版本时同时更新 `versionName` 并递增 `versionCode`。
- 正式版更新说明从上一个正式版生成，预发布版从上一个预发布版生成；首次发布该类型时使用完整提交历史。
- 同一版本已公开发布时跳过，不覆盖已有附件。
- 测试、静态检查、构建及签名验证通过后，上传 APK 和 SHA-256 校验文件。

首次使用时，在仓库 **Settings → Secrets and variables → Actions** 配置：

| Repository Secret | 内容 |
| --- | --- |
| `MUSICENHANCE_KEYSTORE_BASE64` | 签名证书文件的 Base64 文本 |
| `MUSICENHANCE_KEY_ALIAS` | 密钥别名 |
| `MUSICENHANCE_STORE_PASSWORD` | 证书库密码 |
| `MUSICENHANCE_KEY_PASSWORD` | 密钥密码；与证书库密码相同时可省略 |

例如，在本机 PowerShell 中将证书编码并复制到剪贴板，再粘贴到对应 Secret：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('C:\Keys\musicenhance.jks')) | Set-Clipboard
```

Base64 内容仍属于私密证书信息，不要提交到仓库。请使用与现有安装包一致的证书。

工作流使用 GitHub 提供的 `GITHUB_TOKEN` 发布，需允许 `contents: write`。推送标签时，标签必须与源码版本一致，例如 `2.1` 对应 `v2.1`；正常推送 `main` 无需手动建标签。当前版本规则支持数字正式版和 beta，不接受 alpha、rc 等其他后缀。

可在 **Actions → Build and publish release** 查看进度。附件上传完整后才公开发布；失败时可对同一提交重新运行任务。Actions 构建产物保留 14 天，Releases 附件不受该期限影响。

## 开发与扩展

新增代码与重构遵循 [项目开发约定](AGENTS.md)，统一结构、命名、播放器适配边界及生命周期管理要求。

播放器界面通过统一的 `PlayerController` 获取状态和执行操作；歌词、封面分别通过 `LyricsProvider`、`ArtworkProvider` 接入。QQ 私有接口集中在 `adapter/qq`，通用界面、缓存和歌词交互无需直接依赖 QQ 类。

后续接入其他播放器，需要补充应用识别、原生控制和数据接口，并进行对应版本的真机验证；添加包名或作用域本身不会完成适配。

| 源码位置 | 职责 |
| --- | --- |
| [adapter](app/src/main/java/com/jaco/musicenhance/adapter) | 应用识别、控制器注册和原生接口适配 |
| [player/artwork](app/src/main/java/com/jaco/musicenhance/player/artwork) | 封面提供器、下载缓存、队列窗口和背景过渡 |
| [player/lyrics](app/src/main/java/com/jaco/musicenhance/player/lyrics) | 歌词提供器和交互状态 |
| [player/ui](app/src/main/java/com/jaco/musicenhance/player/ui) | 外屏布局、歌词视图和播放控件 |
| [device](app/src/main/java/com/jaco/musicenhance/device) | 外屏检测、摄像头定位与设备布局 |
| [hook](app/src/main/java/com/jaco/musicenhance/hook) | 模块入口及系统、宿主应用钩子 |

接入新播放器时，需要实现应用 Profile 并注册控制器工厂，补充原生数据接口，同时更新模块作用域和应用查询配置。缺少的可选能力应返回未知或不支持状态，不要套用 QQ 的私有接口。

单元测试和构建检查不能替代真机验证，特别是 HyperOS 外屏切换、摄像头定位及 QQ 私有接口行为。
