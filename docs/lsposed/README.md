# 音乐外屏增强 · MusicEnhance

为 **Xiaomi MIX Flip / MIX Flip 2** 设计的外屏音乐增强模块，包名为 `vip.chener.musicenhance`，基于现代 **libxposed API 102**。

在外屏打开已适配音乐应用的播放页，即可进入统一的全屏播放器。音乐继续由原应用播放，模块主程序仅负责设置。

[源码与完整文档](https://github.com/daixu0502/MusicEnhance) · [下载版本](https://github.com/daixu0502/MusicEnhance/releases) · [问题反馈](https://github.com/daixu0502/MusicEnhance/issues)

## 功能

- 全屏专辑背景、渐变模糊和封面切换效果。
- 避让摄像头的播放控件，支持外屏正反方向布局与旋转动画。
- 播放/暂停、上下首、进度定位，以及宿主支持的循环和喜欢操作。
- 逐句滚动歌词、手动浏览和点击歌词定位并播放。
- 后台分析音频的频谱律动，暂停后平滑回落。
- 按音乐应用独立启用，可选增强播放器前台常亮。
- 支持高清封面缓存和邻近队列预取；椒盐复用原生缓存，LX-X 仅使用宿主已加载图片。

## 使用要求

| 项目 | 要求 |
| --- | --- |
| 设备 | Xiaomi MIX Flip（`ruyi`）/ MIX Flip 2（`bixi`） |
| 系统 | 小米 HyperOS，Android 14（API 34）及以上 |
| 架构 | `arm64-v8a` |
| 框架 | 已正常运行、支持现代 libxposed API 102 的 LSPosed 环境 |
| 入口 | 已启用音乐应用的外屏播放页 |

最低 Android 版本代表安装要求，外屏功能依赖小米系统实现。现有真机验证主要来自 MIX Flip 2，其他系统版本与 MIX Flip 一代仍需实际验证。

## 音乐应用

| 应用 | 适配依据 | 包名 |
| --- | --- | --- |
| QQ 音乐 | 自动接口适配（Beta）；20.9.5.8 有既有外屏验证记录 | `com.tencent.qqmusic` |
| Apple Music | 6.5.2 | `com.apple.android.music` |
| 酷我音乐 | 12.2.2.4 | `cn.kuwo.player` |
| 酷狗概念版 | 5.2.9 | `com.kugou.android.lite` |
| 酷狗音乐（普通版） | 20.8.2 / 20.8.4 | `com.kugou.android` |
| 椒盐音乐 | 自动接口适配（Beta），已核对 12.3.2 / 12.4.0 / 12.4.1 官方版 | `com.salt.music` |
| LX-X Music | 26.09.20，待真机验证 | `com.lxwalnut.music.mobile` |
| Spotify | 9.1.90.2270，待真机验证 | `com.spotify.music` |

以上为既有适配依据，不代表包名迁移后已重新完成真机验证。自动接口定位不能保证适应未来所有版本；接口失效时部分功能可能不可用。详细验证记录和限制见[源码仓库 README](https://github.com/daixu0502/MusicEnhance#支持范围)。

## 安装与启用

1. 下载并安装签名 Release APK；标记为 Pre-release 的版本为测试版。
2. 在 LSPosed 中启用「音乐外屏增强」，确认静态作用域已加载。
3. 打开模块设置，在「功能」页开启需要使用的音乐应用；按需开启外屏常亮。
4. 首次启用，或更新系统侧 Hook、作用域时，重启手机。
5. 在外屏打开音乐应用并进入播放页。仅调整应用开关或更新应用侧适配后，强行停止并重新打开对应音乐应用。

静态作用域包含：

```text
system
com.android.systemui
com.tencent.qqmusic
com.apple.android.music
cn.kuwo.player
com.kugou.android.lite
com.kugou.android
com.salt.music
com.lxwalnut.music.mobile
com.spotify.music
```

系统框架用于外屏全屏及小组件策略，System UI 用于旋转动画；无需添加外屏桌面作用域。静态作用域由 APK 提供，手动勾选无法为旧版本增加新适配。

## 限制与排查

- 内屏保留原生播放器，模块设置页没有独立播放入口。
- 歌词、高清封面、循环及收藏能力取决于宿主数据与接口，也受登录、歌曲和网络状态影响。
- 椒盐和 LX-X 的喜欢图标仅作固定显示，不代表真实收藏；酷狗概念版 AI 歌曲暂不支持收藏适配。
- 预取覆盖可读取队列中的前后各三首；随机队列、快速切歌和远距离跳转不保证缓存命中，预取会使用网络。
- 首次扫描私有接口时，增强入口或部分功能可能暂不可用；扫描完成后可重试进入。
- 如果仍显示原生播放器，请检查设备、框架、模块状态和应用开关，强行停止并重开宿主；系统侧更新后需要重启。

## 反馈

请到[源码仓库 Issues](https://github.com/daixu0502/MusicEnhance/issues)提供设备型号、Android / HyperOS 版本、模块版本、音乐应用完整版本、复现步骤，以及必要的截图或录像。旋转问题请注明进入前方向及内外屏切换过程，歌词或封面问题请提供歌曲与录音版本。

可附复现后的 LSPosed 日志，模块标签为 `MusicEnhance`；分享前检查并移除个人信息。
