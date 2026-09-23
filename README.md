# 音乐外屏增强

适用于小米 MIX Flip / MIX Flip 2 的音乐外屏播放器模块，使用现代 LSPosed API 102。

## 功能

- 为 QQ 音乐启用小米小外屏兼容策略与合盖连续运行。
- Miuix 风格设置页，可独立开关“Hook QQ 音乐”。
- 仅在 MIX Flip 近方形外屏显示专用播放器，内屏保持 QQ 音乐原界面。
- 从 QQ 音乐的系统 `MediaSession` 同步歌名、歌手、封面、播放状态和进度。
- 支持播放/暂停、上一首、下一首和拖动进度。
- 专辑封面全屏铺底：通过 QQ 音乐 20.8.5.8 的封面地址生成接口优先请求 1500×1500 图片，失败时尝试 1200×1200、800×800；按实际图片尺寸与原生播放器封面比较选取。后台下载并缓存，换歌时丢弃过期结果，不经过媒体通知的缩略图传输。原图缺失时保留原有封面。
- 从屏幕中部向歌名所在边缘分三层逐渐增强模糊，高清源图不改变这一效果。
- 从 QQ 音乐的 `AudioTrack` PCM 数据生成每 10ms 一帧的低频律动，驱动 25 条中心向两侧展开的频谱；颜色从专辑封面主色生成渐变。暂停后从当前高度在约 800ms 内平滑回落，不继续播放预缓冲动画。
- 在 QQ 音乐界面进程与 `QQPlayerService` 进程之间同步歌曲信息、控制命令和频谱。
- 播放页使用系统正反竖屏方向策略，并在启动前拦截 QQ 自带横屏播放器。摄像头位置优先从 `DisplayManagerGlobal.getDisplayInfo` 的实时缺口读取；反向首页进入播放器时，显示缺口缺失则使用窗口的有效摄像头缺口，最后才按旋转值定位，避免旧方向值导致对角错位。
- 播放页强制使用完整外屏区域，并提供返回 QQ 音乐原界面的按钮。
- 返回 QQ 音乐首页后恢复系统原生半屏布局；再次打开播放器时自动恢复全屏。
- 循环按钮在后台直接调用 QQ 播放服务，依次切换顺序播放、列表循环、单曲循环和随机播放，不创建菜单。模式值以 QQ 音乐 20.8.5.8 为准。
- 喜欢状态通过 QQ 的 `UserDataManager.isILike` 查询当前歌曲；收藏数据未初始化时显示读取中，点击仍由 QQ 原生收藏控件处理。
- 返回键避开状态栏，正向在左上、反向在右上。
- 反向时返回箭头同步旋转 180°。QQ 首页明确启用原生外屏小部件支持，防止播放器退出后小部件保留隐藏状态；仅在外屏首页移除多余导航栏背景，切回内屏恢复原设置。
- 播放器图标统一采用 24dp 画布、1.8dp 圆头线条，保持一致视觉大小；触摸区域保留原尺寸。
- 正反切换通过 HyperOS 系统窗口动画整页旋转 180°，约 620ms，采用先加速后减速的平滑曲线。只匹配 MIX Flip 的 QQ 播放器外屏正反切换，内外屏切换、其他应用与横屏旋转不修改。
- 安装应用内提供模块开关和启用指引；播放器仅在 QQ 音乐外屏内使用。

## 安装

1. 安装 `app/build/outputs/apk/debug/app-debug.apk`。
2. 在支持 libxposed API 102 的 LSPosed 框架中启用模块。
3. 保持固定作用域中的“系统框架”“QQ 音乐”和“系统界面（com.android.systemui）”启用；系统界面作用域用于整页旋转动画。
4. 打开模块主程序，启用“Hook QQ 音乐”。
5. 首次启用或更新模块后重启手机，再从外屏桌面启动 QQ 音乐。只切换模块内开关时，强制停止并重新打开 QQ 音乐即可。

## 构建

```powershell
.\gradlew.bat :app:assembleDebug
```

### 签名 release

正式版使用 AGP 9.3 的 `optimization.enable = true`，开启 R8 压缩、混淆与资源裁剪。
`app/src/release/keepRules/xposed.keep` 保留 LSPosed 从资源文件加载的模块入口。
证书通过以下进程环境变量提供，密码不写入项目：

- `MUSICENHANCE_KEYSTORE`：证书绝对路径。
- `MUSICENHANCE_KEY_ALIAS`：密钥别名。
- `MUSICENHANCE_STORE_PASSWORD`：证书库密码。
- `MUSICENHANCE_KEY_PASSWORD`：密钥密码；省略时使用证书库密码。

```powershell
.\gradlew.bat --no-configuration-cache --no-daemon :app:assembleRelease
```

未配置完整签名环境变量时输出未签名 release。正式签名与旧 debug 签名不同，无法直接覆盖旧 debug 安装；请先备份模块设置，再手动卸载旧模块并安装正式版、重新启用作用域。构建不会自动卸载手机应用。

## 兼容设计

当前适配使用小米 HyperOS 中的 `ApplicationCompatManager` 和
`InterceptActivityController` 外屏策略入口。各入口独立安装；系统版本缺少某个入口时，
其余功能仍会继续加载，错误会写入 LSPosed 日志，标签为 `MusicEnhance`。

扩展其他播放器的具体步骤和约束见 [代码结构与接入指南](docs/architecture.md)。当前实际适配仍只有 QQ 音乐，注册新应用后还需实现其原生控制接口并验证目标版本。
