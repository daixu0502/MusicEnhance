package com.jaco.musicenhance.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
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
import com.jaco.musicenhance.player.ui.CoverPlayerView
import java.util.WeakHashMap

internal object CoverPlayerInjector {
    private val overlays = WeakHashMap<Activity, CoverPlayerView>()
    private val layoutListeners = WeakHashMap<Activity, View.OnLayoutChangeListener>()
    private val suppressed = WeakHashMap<Activity, Boolean>()
    private val originalOrientations = WeakHashMap<Activity, Int>()
    private val backCallbacks = WeakHashMap<Activity, OnBackInvokedCallback>()
    private val mainHandler = Handler(Looper.getMainLooper())


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

    fun update(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val profile = MusicAppRegistry.find(activity.packageName) ?: return
        val decor = activity.window.decorView as? ViewGroup ?: return
        observeSizeChanges(activity, decor)
        if (suppressed[activity] == true) return
        if (!isPlayerActivityName(activity.javaClass.name)) {
            removeOverlay(activity, decor)
            CoverHomeAppearance.update(activity)
            return
        }
        val enabled = hookEnabled(profile)
        val isCoverScreen = CoverScreenDetector.isCoverScreen(activity)
        if (!enabled || !isCoverScreen) {
            removeOverlay(activity, decor)
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
            player.requestApplyInsets()
            player.refreshDisplayLayout()
            return
        }

        prepareOrientation(activity)
        activity.window.setDecorFitsSystemWindows(false)
        val player = CoverPlayerView(
            activity,
            controller = MusicAppAdapters.create(profile, activity, decor),
            onDismiss = ::dismissToMusicHome,
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
        activity.window.insetsController?.hide(
            WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(),
        )
    }

    private fun dismissToMusicHome() {
        val playerActivities = overlays.keys.filter {
            !it.isFinishing && !it.isDestroyed && isPlayerActivityName(it.javaClass.name)
        }
        playerActivities.forEach { activity ->
            suppressed[activity] = true
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
        val callback = OnBackInvokedCallback(::dismissToMusicHome)
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
        overlays.remove(activity)?.let(decor::removeView)
        originalOrientations.remove(activity)?.let { original ->
            if (!activity.isFinishing && !activity.isDestroyed) activity.requestedOrientation = original
        }
    }

    fun onDestroyed(activity: Activity) {
        unregisterBackCallback(activity)
        val decor = activity.window.peekDecorView() as? ViewGroup
        layoutListeners.remove(activity)?.let { decor?.removeOnLayoutChangeListener(it) }
        overlays.remove(activity)?.let { decor?.removeView(it) }
        originalOrientations.remove(activity)
        suppressed.remove(activity)
        CoverHomeAppearance.onDestroyed(activity)
    }

    private fun observeSizeChanges(activity: Activity, decor: ViewGroup) {
        if (layoutListeners.containsKey(activity)) return
        val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                decor.post { update(activity) }
            }
        }
        layoutListeners[activity] = listener
        decor.addOnLayoutChangeListener(listener)
    }
}
