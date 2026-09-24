package com.jaco.musicenhance.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.jaco.musicenhance.Prefs
import com.jaco.musicenhance.adapter.MusicAppAdapters
import com.jaco.musicenhance.adapter.MusicAppRegistry
import com.jaco.musicenhance.adapter.isHorizontalPlayerActivityName
import com.jaco.musicenhance.adapter.isPlayerActivityName
import com.jaco.musicenhance.device.CoverHomeAppearance
import com.jaco.musicenhance.device.CoverScreenDetector
import com.jaco.musicenhance.hook.MusicEnhanceModule
import com.jaco.musicenhance.hook.hookEnabled
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import com.jaco.musicenhance.player.ui.CoverPlayerView
import java.util.WeakHashMap

internal object CoverPlayerInjector {
    private val overlays = WeakHashMap<Activity, CoverPlayerView>()
    private val overlayLayers = WeakHashMap<Activity, PlayerOverlayLayer>()
    private val windowObservers = WeakHashMap<Activity, PlayerWindowObserver>()
    private val suppressed = WeakHashMap<Activity, Boolean>()
    private val originalOrientations = WeakHashMap<Activity, Int>()
    private val backCallbacks = WeakHashMap<Activity, OnBackInvokedCallback>()
    private val embeddedPages = WeakHashMap<Activity, EmbeddedPlayerPage>()
    private val windowLayouts = WeakHashMap<Activity, PlayerWindowLayout>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Native UI policies yield only while this Activity is actually hosting our cover player. */
    fun ownsSystemBars(activity: Activity?): Boolean {
        activity ?: return false
        if (activity.isFinishing || activity.isDestroyed || suppressed[activity] == true) return false
        val player = overlays[activity] ?: return false
        if (!player.isAttachedToWindow || !player.isShown) return false
        // A fold transition may change the physical panel before the overlay has been removed.
        return CoverScreenDetector.isCoverScreen(activity)
    }

    fun prepareOrientation(activity: Activity) {
        originalOrientations.putIfAbsent(activity, activity.requestedOrientation)
        // USER_PORTRAIT lets Android choose portrait or reverse portrait from the sensor while
        // rejecting both landscape rotations. HyperOS then owns the real rotation animation and
        // reports the corresponding camera cutout without a competing module orientation request.
        val target = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        if (activity.requestedOrientation != target) {
            activity.requestedOrientation = target
        }
    }

    fun onCreated(activity: Activity) {
        if (isPlayerActivityName(activity.javaClass.name)) observeWindow(activity)
    }

    fun onResumed(activity: Activity) {
        safeHook("native player page") { MusicAppAdapters.onActivityReady(activity) }
        observeWindow(activity)
    }

    fun showEmbeddedPage(activity: Activity, page: EmbeddedPlayerPage) {
        if (activity.isFinishing || activity.isDestroyed) return
        val profile = MusicAppRegistry.find(activity.packageName) ?: return
        if (activity.javaClass.name !in profile.embeddedPlayerActivityNames) return
        embeddedPages[activity] = page
        observeWindow(activity)
    }

    fun hideEmbeddedPage(activity: Activity, identity: Any) {
        if (embeddedPages[activity]?.identity != identity) return
        embeddedPages.remove(activity)
        (activity.window.peekDecorView() as? ViewGroup)?.let { removeOverlay(activity, it) }
    }

    private fun observeWindow(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || suppressed[activity] == true) return
        if (MusicAppRegistry.find(activity.packageName) == null) return
        val decor = activity.window.decorView
        windowObservers.getOrPut(activity) {
            PlayerWindowObserver(decor) {
                safeHook("player window update") { update(activity) }
            }
        }.start()
    }

    fun onPaused(activity: Activity) {
        windowObservers[activity]?.stop()
    }

    private fun update(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val profile = MusicAppRegistry.find(activity.packageName) ?: return
        val decor = activity.window.decorView as? ViewGroup ?: return
        if (suppressed[activity] == true) return
        if (!isPlayerActivityName(activity.javaClass.name) && activity !in embeddedPages) {
            removeOverlay(activity, decor)
            CoverHomeAppearance.update(activity)
            return
        }
        val enabled = hookEnabled(profile)
        val isCoverScreen = CoverScreenDetector.isCoverScreen(activity)
        if (!enabled || !isCoverScreen) {
            removeOverlay(activity, decor)
            safeHook("cover player unavailable") { MusicAppAdapters.onCoverPlayerUnavailable(activity) }
            module.log(
                Log.INFO,
                MusicEnhanceModule.TAG,
                "Overlay skipped; enabled=$enabled, coverScreen=$isCoverScreen, activity=${activity.javaClass.name}",
            )
            return
        }
        if (isHorizontalPlayerActivityName(activity.javaClass.name)) {
            suppressHorizontalPlayer(activity)
            return
        }
        overlays[activity]?.takeIf { it.parent != null }?.let { player ->
            overlayLayers[activity]?.refresh()
            player.requestApplyInsets()
            player.refreshDisplayLayout()
            return
        }

        windowLayouts.getOrPut(activity) {
            val title = if (activity in embeddedPages) profile.embeddedPlayerWindowTitle(activity.javaClass.name) else null
            PlayerWindowLayout(activity.window, title).also {
                it.enter()
                moduleInfo("Cover player window layout acquired; activity=${activity.javaClass.name}, cutoutMode=${activity.window.attributes.layoutInDisplayCutoutMode}")
            }
        }
        if (activity in embeddedPages) {
            // Let the title reach WindowManager before requesting bounds/orientation recalculation.
            decor.post {
                if (activity in windowLayouts && !activity.isDestroyed && !activity.isFinishing) prepareOrientation(activity)
            }
        } else prepareOrientation(activity)
        // Embedded pages share their window with the home UI; preserve its decor policy.
        if (activity !in embeddedPages) activity.window.setDecorFitsSystemWindows(false)
        val player = CoverPlayerView(
            activity,
            window = activity.window,
            controller = MusicAppAdapters.create(profile, activity, embeddedPages[activity]?.nativeRoot?.get() ?: decor),
            onDismiss = { dismissPlayer(activity) },
            keepScreenOnRequested = {
                CoverScreenDetector.isCoverScreen(activity) && runCatching {
                    module.getRemotePreferences(Prefs.NAME).getBoolean(Prefs.KEEP_COVER_SCREEN_ON, false)
                }.getOrDefault(false)
            },
        ).apply {
            tag = PLAYER_OVERLAY_TAG
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        decor.addView(
            player,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        overlays[activity] = player
        overlayLayers[activity] = PlayerOverlayLayer(decor, player) {
            moduleInfo("Restored player above late host content; activity=${activity.javaClass.name}")
        }.also { it.start() }
        registerBackCallback(activity)
        player.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                unregisterBackCallback(activity)
            }
        })
        module.log(
            Log.INFO,
            MusicEnhanceModule.TAG,
            "Outer-screen player attached; activity=${activity.javaClass.name}",
        )
    }

    private fun dismissToMusicHome() {
        val playerActivities = overlays.keys.filter {
            !it.isFinishing && !it.isDestroyed && isPlayerActivityName(it.javaClass.name)
        }
        playerActivities.forEach { activity ->
            suppressed[activity] = true
            onPaused(activity)
            overlays[activity]?.prepareForDismissal()
            unregisterBackCallback(activity)
            module.log(
                Log.INFO,
                MusicEnhanceModule.TAG,
                "Closing music player activity; activity=${activity.javaClass.name}",
            )
            activity.finish()
            mainHandler.postDelayed({
                if (!activity.isDestroyed) {
                    val decor = activity.window.decorView as? ViewGroup
                    overlays.remove(activity)?.let { decor?.removeView(it) }
                    originalOrientations.remove(activity)?.let { activity.requestedOrientation = it }
                }
            }, 350L)
        }
    }

    private fun dismissPlayer(activity: Activity) {
        val page = embeddedPages[activity]
        if (page == null) {
            dismissToMusicHome()
            return
        }
        // Dismiss the native sheet/fragment too, so the next tap can enter it again.
        if (runCatching(page.dismiss).getOrDefault(false)) {
            hideEmbeddedPage(activity, page.identity)
        }
    }

    fun suppressHorizontalPlayer(activity: Activity) {
        if (!isHorizontalPlayerActivityName(activity.javaClass.name)) return
        runCatching {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            activity.finish()
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }.onFailure {
            activity.finish()
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
        moduleInfo("Suppressed music app horizontal player activity")
    }

    private fun registerBackCallback(activity: Activity) {
        if (backCallbacks.containsKey(activity)) return
        val callback = OnBackInvokedCallback { dismissPlayer(activity) }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        backCallbacks[activity] = callback
    }

    private fun unregisterBackCallback(activity: Activity) {
        val callback = backCallbacks.remove(activity) ?: return
        runCatching { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    private fun removeOverlay(activity: Activity, decor: ViewGroup) {
        unregisterBackCallback(activity)
        overlayLayers.remove(activity)?.stop()
        overlays.remove(activity)?.let(decor::removeView)
        windowLayouts.remove(activity)?.restore()
        originalOrientations.remove(activity)?.let { original ->
            if (!activity.isFinishing && !activity.isDestroyed) activity.requestedOrientation = original
        }
    }

    fun onDestroyed(activity: Activity) {
        unregisterBackCallback(activity)
        overlayLayers.remove(activity)?.stop()
        val decor = activity.window.peekDecorView() as? ViewGroup
        windowObservers.remove(activity)?.stop()
        overlays.remove(activity)?.let { decor?.removeView(it) }
        windowLayouts.remove(activity)?.restore()
        originalOrientations.remove(activity)
        suppressed.remove(activity)
        embeddedPages.remove(activity)
        CoverHomeAppearance.onDestroyed(activity)
    }

}
