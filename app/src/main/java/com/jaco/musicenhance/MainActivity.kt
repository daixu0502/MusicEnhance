package com.jaco.musicenhance

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.jaco.musicenhance.adapter.MusicAppProfile
import com.jaco.musicenhance.adapter.MusicAppRegistry
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        setContent {
            val controller = remember { ThemeController() }
            val dark = isSystemInDarkTheme()
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                )
            }
            MiuixTheme(controller = controller) {
                MainScreen(
                    deviceName = supportedDeviceName(),
                    installedPackages = MusicAppRegistry.profiles.filter {
                        runCatching {
                            packageManager.getApplicationInfo(it.packageName, PackageManager.ApplicationInfoFlags.of(0))
                        }.isSuccess
                    }.map { it.packageName }.toSet(),
                    onOpenLSPosed = {
                        packageManager.getLaunchIntentForPackage(LSPOSED_PACKAGE)?.let(::startActivity)
                    },
                )
            }
        }
    }

    private fun supportedDeviceName(): String? = when (Build.DEVICE.lowercase()) {
        "ruyi" -> "Xiaomi MIX Flip"
        "bixi" -> "Xiaomi MIX Flip 2"
        else -> Build.MODEL.takeIf { it.contains("MIX Flip", ignoreCase = true) }
    }

    private companion object {
        const val LSPOSED_PACKAGE = "org.lsposed.manager"
    }
}

@Composable
private fun MainScreen(
    deviceName: String?,
    installedPackages: Set<String>,
    onOpenLSPosed: () -> Unit,
) {
    val context = LocalContext.current
    val connected = XposedServiceState.isConnected
    var selectedPage by rememberSaveable { mutableIntStateOf(0) }

    BackHandler(enabled = selectedPage != 0) { selectedPage = 0 }

    DisposableEffect(Unit) {
        context.deleteSharedPreferences(Prefs.NAME)
        XposedServiceState.ensureRegistered()
        onDispose { }
    }

    MainSettingsScreen(
        connected = connected,
        deviceName = deviceName,
        installedPackages = installedPackages,
        selectedPage = selectedPage,
        onSelectPage = { selectedPage = it },
        onOpenLSPosed = onOpenLSPosed,
    )
}

@Composable
private fun MainSettingsScreen(
    connected: Boolean,
    deviceName: String?,
    installedPackages: Set<String>,
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
    onOpenLSPosed: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val listStates = MainPage.entries.map { rememberLazyListState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (selectedPage == 0) stringResource(R.string.app_name) else MainPage.entries[selectedPage].title,
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            NavigationBar {
                MainPage.entries.forEachIndexed { index, page ->
                    NavigationBarItem(
                        selected = selectedPage == index,
                        onClick = { onSelectPage(index) },
                        icon = page.icon,
                        label = page.title,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = page.title
                            role = Role.Tab
                            selected = selectedPage == index
                            onClick(label = "切换到${page.title}") { onSelectPage(index); true }
                        },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = selectedPage,
            transitionSpec = {
                fadeIn(tween(SETTINGS_TRANSITION_DURATION_MS)) togetherWith
                    fadeOut(tween(SETTINGS_TRANSITION_DURATION_MS))
            },
            label = "main-page",
        ) { page ->
            LazyColumn(
                state = listStates[page],
                modifier = Modifier.fillMaxHeight().scrollEndHaptic().overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = padding,
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (MainPage.entries[page]) {
                            MainPage.INFO -> {
                                ActivationCard(connected)
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    ArrowPreference(
                                        title = "LSPosed 作用域",
                                        summary = "API 102 · 系统框架 · 系统界面 · 各音乐应用",
                                        onClick = onOpenLSPosed,
                                    )
                                }
                                DeviceCard(deviceName, installedPackages)
                            }
                            MainPage.FUNCTIONS -> {
                                Text(
                                    "音乐应用",
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                    color = colorScheme.onSurfaceVariantSummary,
                                    fontSize = 14.sp,
                                )
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column { MusicAppRegistry.profiles.forEach { MusicAppPreference(it) } }
                                }
                                PlayerPreferences()
                            }
                            MainPage.ABOUT -> AboutCards()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCards() {
    val uriHandler = LocalUriHandler.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.app_name), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME), color = colorScheme.onSurfaceVariantSummary)
            Text("为 Xiaomi MIX Flip 系列打造的音乐外屏播放器", color = colorScheme.onSurfaceVariantSummary)
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(title = "GitHub 项目", summary = "MusicEnhance",
            onClick = { uriHandler.openUri(PROJECT_URL) })
        ArrowPreference(title = "作者", summary = "Jaco",
            onClick = { uriHandler.openUri(AUTHOR_URL) })
        ArrowPreference(title = "反馈问题", summary = "提交日志、应用版本与复现步骤",
            onClick = { uriHandler.openUri("$PROJECT_URL/issues") })
        ArrowPreference(title = "第三方开源组件", summary = "依赖来源与授权说明",
            onClick = { uriHandler.openUri("$PROJECT_URL/blob/main/THIRD_PARTY_NOTICES.md") })
    }
}

@Composable
private fun MusicAppPreference(profile: MusicAppProfile) {
    val prefs = XposedServiceState.prefs
    val connected = XposedServiceState.isConnected
    var enabled by remember(prefs, profile.packageName) {
        mutableStateOf(Prefs.isHookEnabled(prefs, profile.enabledPreference))
    }
    SwitchPreference(
        title = "Hook ${profile.displayName}",
        summary = when {
            !connected -> "请先在 LSPosed 中启用模块"
            enabled -> "已启用；重启 ${profile.displayName} 后生效"
            else -> "已关闭；重启 ${profile.displayName} 后生效"
        },
        checked = enabled,
        onCheckedChange = { checked ->
            val remotePrefs = prefs ?: return@SwitchPreference
            enabled = checked
            remotePrefs.edit { putBoolean(profile.enabledPreference, checked) }
        },
    )
}

@Composable
private fun PlayerPreferences() {
    val prefs = XposedServiceState.prefs
    val connected = XposedServiceState.isConnected
    var keepScreenOn by remember(prefs) {
        mutableStateOf(prefs?.getBoolean(Prefs.KEEP_COVER_SCREEN_ON, false) ?: false)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            title = "播放器外屏常亮",
            summary = if (connected) "进入外屏播放器后防止自动息屏锁屏，退出后恢复；仍可手动锁屏"
                else "请先在 LSPosed 中启用模块",
            checked = keepScreenOn,
            onCheckedChange = { checked ->
                val remotePrefs = prefs ?: return@SwitchPreference
                keepScreenOn = checked
                remotePrefs.edit { putBoolean(Prefs.KEEP_COVER_SCREEN_ON, checked) }
            },
        )
    }
}

@Composable
private fun ActivationCard(connected: Boolean) {
    val dark = isSystemInDarkTheme()
    val background = when {
        connected && dark -> ComposeColor(0xFF1A3825)
        connected -> ComposeColor(0xFFDFFAE4)
        dark -> ComposeColor(0xFF3A1E22)
        else -> ComposeColor(0xFFFFE3E6)
    }
    val statusColor = when {
        connected && dark -> ComposeColor(0xFF74D79B)
        connected -> ComposeColor(0xFF24894B)
        dark -> ComposeColor(0xFFFF939C)
        else -> ComposeColor(0xFFBE4050)
    }.copy(alpha = if (dark) 0.28f else 0.20f)
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp)
                .background(background).clipToBounds(),
            contentAlignment = Alignment.CenterStart,
        ) {
            ActivationStatusMark(
                connected = connected,
                color = statusColor,
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = 10.dp, y = 14.dp).size(96.dp),
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 92.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (connected) "模块已激活" else "模块未激活",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                    modifier = Modifier.padding(top = 4.dp),
                    color = colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun ActivationStatusMark(connected: Boolean, color: ComposeColor, modifier: Modifier = Modifier) {
    // Decorative watermark; the adjacent text already announces the activation state.
    Canvas(modifier = modifier) {
        val unit = size.minDimension
        val stroke = Stroke(width = unit * 0.055f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color = color, radius = unit * 0.40f, style = stroke)
        if (connected) {
            val check = Path().apply {
                moveTo(unit * 0.29f, unit * 0.50f)
                lineTo(unit * 0.44f, unit * 0.65f)
                lineTo(unit * 0.72f, unit * 0.36f)
            }
            drawPath(path = check, color = color, style = stroke)
        } else {
            drawLine(
                color = color,
                start = Offset(unit * 0.50f, unit * 0.29f),
                end = Offset(unit * 0.50f, unit * 0.53f),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round,
            )
            drawCircle(color = color, radius = unit * 0.032f, center = Offset(unit * 0.50f, unit * 0.68f))
        }
    }
}

@Composable
private fun DeviceCard(deviceName: String?, installedPackages: Set<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("兼容状态", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(
                deviceName?.let { "设备：$it" } ?: "设备：请在 MIX Flip 1 或 2 使用",
                color = colorScheme.onSurfaceVariantSummary,
            )
            MusicAppRegistry.profiles.forEach { profile ->
                Text(
                    "${profile.displayName}：" + if (profile.packageName in installedPackages) "已安装" else "未检测到",
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

private const val SETTINGS_TRANSITION_DURATION_MS = 320
private const val PROJECT_URL = "https://github.com/daixu0502/MusicEnhance"
private const val AUTHOR_URL = "https://github.com/daixu0502"

private enum class MainPage(val title: String) {
    INFO("信息"), FUNCTIONS("功能"), ABOUT("关于");
    val icon get() = when (this) {
        INFO -> MainNavigationIcons.information
        FUNCTIONS -> MainNavigationIcons.functions
        ABOUT -> MainNavigationIcons.about
    }
}
