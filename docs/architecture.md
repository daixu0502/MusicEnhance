# 代码结构与播放器接入

当前已实现的适配仍是 **QQ 音乐 20.8.5.8 + 小米 MIX Flip / MIX Flip 2**。下面的接口用于后续扩展，并不表示其他播放器已经支持。

## 职责划分

| 目录 | 职责 |
| --- | --- |
| `adapter` | 注册播放器包名、首页/播放页识别规则，创建对应控制器，查找原生控件 |
| `adapter/qq` | QQ 私有反射接口、高清封面、收藏查询、循环模式映射 |
| `player` | `PlayerController` 统一接口；外屏覆盖层的创建、移除和生命周期 |
| `player/model` | 播放快照、循环模式、可空的收藏状态 |
| `player/media` | MediaSession 传输控制、进度推算、同应用跨进程通信 |
| `player/audio` | PCM 低频分析、缓冲、暂停后回落 |
| `player/ui` | 播放页布局、渐变模糊、按钮图标、进度条、摄像头间频谱绘制 |
| `device` | MIX Flip 外屏检测、摄像头位置、首页导航栏恢复 |
| `hook` | API 102 入口、Android 生命周期/音频钩子、HyperOS 全屏及旋转策略 |

入口根据 `MusicAppRegistry` 选择已启用的应用；`MusicAppAdapters` 创建其 `PlayerController`。界面只调用控制器，QQ 的类名和反射方法留在 `adapter/qq`。默认 `MediaSessionPlayerController` 提供播放、暂停、切歌和进度控制；收藏和循环状态需要应用适配层补充。

## 接入其他播放器

1. 在 `adapter/<app>` 新建 `MusicAppProfile`：填写唯一包名、显示名称、独立的开关键名、首页集合、播放页和横屏播放页规则。优先使用已确认的 Activity 名称，首页不能归类为播放页。只有 Fragment/单 Activity 的应用还需要页面识别扩展，不能直接照搬 QQ 的规则。
2. 将 profile 加入 `MusicAppRegistry.profiles`。主设置页会自动生成开关和安装状态；不提供启动音乐应用的入口。
3. 实现 `PlayerController`，或继承 `MediaSessionPlayerController` 并覆盖该应用的收藏、循环模式和高清封面方法。在 `MusicAppAdapters.create` 中注册工厂。QQ 的 101/103/104/105/106 模式值不可用于其他应用。
4. 将包名加入 `app/src/main/resources/META-INF/xposed/scope.list` 和 `AndroidManifest.xml` 的 `queries`。首次使用需要在 LSPosed 中确认作用域，再启用对应开关。
5. 为识别规则和模式映射补充单元测试；用目标版本真机验证进出播放器、正反方向、内外屏切换、首页小部件、已收藏歌曲、各循环模式、暂停回落和切歌后的封面更新。

## 线程与状态约定

- 界面在主线程调用控制器。返回快照必须快速；反射中的跨进程查询、网络下载和图片解码放到后台，主线程只读缓存。原生 View 的读取和点击留在主线程。
- 收藏数据尚未初始化时返回 `null`，不能当作“未喜欢”；未知循环模式返回 `UNKNOWN`。界面只将与当前歌曲匹配的收藏状态应用到按钮。
- 注册监听必须在移除播放器时解除。每个 Activity 只注册一次布局监听；销毁时清除覆盖层、返回回调、方向缓存和首页栏状态。
- `PlayerProcessBridge` 的广播限定在宿主包内，接收器不对其他应用导出。广播 action 字符串保持稳定，不能因类名重构而随意修改。
- API 102 注入进程的远程偏好只读；仅设置应用通过服务写入用户开关。全屏策略由当前 Activity 类型决定，不再跨进程写入临时“全屏状态”。

## 必须保留的设备行为

- 摄像头定位顺序：有效的实时 DisplayInfo 缺口 → 有效的窗口缺口 → 旋转值兜底。不能只按 Activity 的方向定位，HyperOS 切屏期间可能返回旧值。
- 首页明确支持外屏小部件，播放页不支持；首页保持系统半屏策略。不要使用当前 HyperOS 中显示/隐藏实现不一致的 `showFlipWatch` 接口。
- 正反向通过系统窗口旋转动画处理，横屏播放器入口单独拦截。原有 620ms 旋转、25 条频谱、800ms 暂停回落参数保持不变。
- 小米私有系统入口分别安装并记录失败，避免某个系统版本缺少接口时连带跳过其余入口。QQ 私有接口变化时应更新该适配目录，不能声称任意 QQ 版本兼容。

## 构建与回归

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat --no-configuration-cache --no-daemon :app:assembleRelease :app:lintRelease
```

release 签名变量见 README。`hook.MusicEnhanceModule` 是通过 `META-INF/xposed/java_init.list` 加载的入口，名称必须和 release keep 规则一致。R8 后应验证入口仍保留、API 102 未被打包、APK 签名有效。

本轮单元测试覆盖摄像头定位、正反旋转适用范围、暂停回落、QQ 循环模式及应用/页面识别；不能替代 HyperOS 与 QQ 私有接口的真机验证。
