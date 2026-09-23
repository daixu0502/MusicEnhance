package com.jaco.musicenhance

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.jaco.musicenhance.adapter.MusicAppProfile
import com.jaco.musicenhance.adapter.MusicAppRegistry
import top.yukonga.miuix.kmp.basic.Card
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
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        context.deleteSharedPreferences(Prefs.NAME)
        XposedServiceState.ensureRegistered()
        onDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxHeight().scrollEndHaptic().overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = padding,
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ActivationCard(connected)

                    MusicAppRegistry.profiles.forEach { profile ->
                        MusicAppPreferences(profile)
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = "LSPosed 作用域",
                            summary = "API 102 · 系统框架 · " +
                                MusicAppRegistry.profiles.joinToString(" · ") { it.displayName } + " · 系统界面",
                            onClick = onOpenLSPosed,
                        )
                    }

                    DeviceCard(deviceName, installedPackages)
                }
            }
        }
    }
}

@Composable
private fun MusicAppPreferences(profile: MusicAppProfile) {
    val prefs = XposedServiceState.prefs
    val connected = XposedServiceState.isConnected
    var enabled by remember(prefs, profile.packageName) {
        mutableStateOf(prefs?.getBoolean(profile.enabledPreference, false) ?: false)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        SwitchPreference(
            title = "Hook ${profile.displayName}",
            summary = when {
                !connected -> "请先在 LSPosed 中启用模块"
                enabled -> "已启用；重启 ${profile.displayName} 后应用外屏播放器"
                else -> "关闭后重启 ${profile.displayName} 即可恢复原界面"
            },
            checked = enabled,
            onCheckedChange = { checked ->
                val remotePrefs = prefs ?: return@SwitchPreference
                enabled = checked
                remotePrefs.edit { putBoolean(profile.enabledPreference, checked) }
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
