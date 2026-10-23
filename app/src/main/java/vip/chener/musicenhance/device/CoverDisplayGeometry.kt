package vip.chener.musicenhance.device

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.DisplayCutout
import vip.chener.musicenhance.hook.moduleInfo

/** Read one DisplayInfo snapshot, without Activity resource compatibility rotation overrides. */
internal object CoverDisplayGeometry {
    data class State(val rotation: Int, val width: Int, val height: Int, val cutout: Rect?) {
        // Some host display caches keep inner-screen/compatibility dimensions after folding.
        // Such a snapshot cannot veto a cover window that has already laid out correctly.
        val isCoverShape: Boolean get() = width > 0 && height > 0 &&
            minOf(width, height).toFloat() / maxOf(width, height) >= 0.60f

        fun matchesCoverWindow(windowWidth: Int, windowHeight: Int): Boolean =
            !isCoverShape || (windowWidth == width && windowHeight == height)
    }

    // Public Activity metrics can contain compatibility rotation overrides; use one physical snapshot.
    // A missing platform API leaves this reader unavailable and the caller uses its layout fallback.
    private val reader by lazy {
        runCatching {
            @SuppressLint("PrivateApi")
            val type = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val global = type.getDeclaredMethod("getInstance").invoke(null)
            val method = type.getDeclaredMethod("getDisplayInfo", Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
            @SuppressLint("PrivateApi")
            val info = Class.forName("android.view.DisplayInfo")
            val rotation = info.getField("rotation")
            val width = info.getField("logicalWidth")
            val height = info.getField("logicalHeight")
            val cutout = info.getField("displayCutout")
            val read: (Int) -> State? = { id ->
                val snapshot = method.invoke(global, id)
                if (snapshot == null) null else State(
                    rotation.getInt(snapshot), width.getInt(snapshot), height.getInt(snapshot),
                    (cutout.get(snapshot) as? DisplayCutout)?.boundingRects
                        ?.maxByOrNull { it.width().toLong() * it.height() }?.let(::Rect),
                )
            }
            read
        }.onFailure { moduleInfo("Physical display geometry unavailable: $it") }.getOrNull()
    }

    fun read(displayId: Int): State? = runCatching { reader?.invoke(displayId) }.getOrNull()
}
