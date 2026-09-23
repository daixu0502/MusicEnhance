package com.jaco.musicenhance.device

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import com.jaco.musicenhance.hook.MusicEnhanceModule
import com.jaco.musicenhance.hook.module
import kotlin.math.max
import kotlin.math.min

internal object CoverScreenDetector {
    private val codenames = setOf("ruyi", "bixi")
    private const val COVER_RATIO = 0.60f

    fun isCoverScreen(activity: Activity): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        val device = Build.DEVICE.lowercase()
        val product = Build.PRODUCT.lowercase()
        val isMixFlip = device in codenames || product in codenames || model.contains("mix flip")
        val isXiaomi = manufacturer.contains("xiaomi")

        val bounds = runCatching { activity.windowManager.currentWindowMetrics.bounds }.getOrElse { Rect() }
        val display = runCatching { activity.display }.getOrNull()
        val mode = runCatching { display?.mode }.getOrNull()
        val config = activity.resources.configuration
        val windowRatio = ratio(bounds.width(), bounds.height())
        val physicalRatio = ratio(mode?.physicalWidth ?: 0, mode?.physicalHeight ?: 0)
        val configRatio = ratio(config.screenWidthDp, config.screenHeightDp)
        val folded = queryXiaomiFoldedState()
        val secondaryDisplay = display?.displayId?.let { it != Display.DEFAULT_DISPLAY } == true

        val result = isXiaomi && isMixFlip && (
            folded == true || secondaryDisplay ||
                windowRatio >= COVER_RATIO || physicalRatio >= COVER_RATIO || configRatio >= COVER_RATIO
            )

        module.log(
            Log.INFO,
            MusicEnhanceModule.TAG,
            buildString {
                append("Cover detection: result=$result")
                append(", manufacturer=${Build.MANUFACTURER}")
                append(", model=${Build.MODEL}")
                append(", device=${Build.DEVICE}")
                append(", product=${Build.PRODUCT}")
                append(", displayId=${display?.displayId}")
                append(", window=${bounds.width()}x${bounds.height()} ratio=$windowRatio")
                append(", physical=${mode?.physicalWidth}x${mode?.physicalHeight} ratio=$physicalRatio")
                append(", config=${config.screenWidthDp}x${config.screenHeightDp}dp")
                append(", smallest=${config.smallestScreenWidthDp}dp")
                append(", folded=$folded")
            },
        )
        return result
    }

    private fun ratio(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        return min(width, height).toFloat() / max(width, height)
    }

    /** null means that this HyperOS build does not expose the private folded-state API. */
    private fun queryXiaomiFoldedState(): Boolean? = runCatching {
        val stubClass = Class.forName("android.sizecompat.MiuiAppSizeCompatModeStub")
        val stub = stubClass.getDeclaredMethod("get").apply { isAccessible = true }.invoke(null)
            ?: return@runCatching null
        val method = stub.javaClass.methods.firstOrNull {
            it.name == "isFlipFolded" && it.parameterCount == 0
        } ?: stub.javaClass.getDeclaredMethod("isFlipFolded").apply { isAccessible = true }
        method.invoke(stub) as? Boolean
    }.getOrNull()
}
