# 1.28 状态栏自动恢复排查

## 代码证据

对比本机保存的 1.25 重构前备份与 1.26：两版均只在添加外屏播放器后调用一次 `WindowInsetsController.hide`，没有阻止 QQ 原生播放器后续的显示请求。由此不能断言“重构删除了持续隐藏功能”；具体触发时序变化仍需要同一路径的真机日志确认。

本机 QQ 音乐 20.8.5.8 APK 的反编译代码存在以下明确路径：

1. `playernew.view.hn`（日志名 `TrafficDataFreeController`）在符合流量提示条件时发送消息 100。
2. `hn$b.handleMessage` 处理 100 时安排 200ms 后显示提示、10,000ms 后处理消息 101。
3. 消息 101 经 `hn.U1` → `hn.e2` → `playercommon.normalplayer.common.i.s(Activity)` 恢复状态栏，同时移除提示。
4. `i.s` 在 Android 30 及以上、非多窗口时调用 `WindowInsetsController.show(statusBars())`，接着调整原生 system UI 标记。
5. `LayersPlayerView.J2` 也调用同一个 `i.s`；`BaseActivity.showStatusBar` 则通过窗口标记请求显示状态栏。

这是实际存在的自动显示源；当前未连接 ADB，尚不能证明用户此次闪现一定来自哪一次调用。不能把编译或既有算法测试当作这条设备路径的验证。

## 修复边界

- `QQMusicWindowHooks` 在上述显示方法执行前判断当前 Activity 是否正在承载已附着、可见的外屏增强播放器，仅在此期间直接返回；不调用其 `show` 或窗口标记修改代码。
- 流量提示消息的其他行为保留，未禁用整个 Handler 或播放器控制器。对已知延迟调用链取消编译内联，防止绕过入口 Hook。
- 首页、模块关闭时未安装的钩子、未附着/已退出的覆盖层以及内屏均不拦截。判断在调用时进行，以覆盖内外屏切换过程。
- 删除 1.27 的可见性监听、焦点重隐藏、控制权监听和下一帧隐藏任务。进入时只设置一次沉浸模式。
- 返回首页时不在即将关闭的播放窗口上主动 `show` 系统栏，避免退出表面闪现；普通覆盖层移除仍恢复保存的可见性。

## 真机检查

安装 1.28 后强制停止并重新启动 QQ 音乐。复现原先出现问题的进入路径，停留至少 15 秒，检查正反方向、切歌、通知栏返回、内外屏切换及返回首页。

LSPosed 日志出现 `Blocked QQ PlayerUtil.showStatusBar before window request`，表示显示请求已在执行前被阻止；出现 `Blocked QQ BaseActivity.showStatusBar before window flags` 表示窗口标记路径被阻止。入口不存在或安装失败会记录独立的错误，不修改所有应用的全局 InsetsController。QQ 更新并更改混淆名称时需重新适配。
