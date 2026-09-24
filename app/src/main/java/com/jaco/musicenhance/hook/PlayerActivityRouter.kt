package com.jaco.musicenhance.hook

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.jaco.musicenhance.Prefs
import com.jaco.musicenhance.adapter.MusicAppAdapters
import com.jaco.musicenhance.adapter.MusicAppRegistry
import com.jaco.musicenhance.device.CoverHomeAppearance
import com.jaco.musicenhance.device.CoverScreenDetector
import com.jaco.musicenhance.adapter.NativePlayerPage
import com.jaco.musicenhance.player.EnhancedPlayerActivity
import com.jaco.musicenhance.player.PlayerActivitySession
import com.jaco.musicenhance.player.PlayerActivitySessions
import com.jaco.musicenhance.player.PlayerWindowObserver
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap

/** Native pages supply data; a separate, common Activity exclusively owns the enhanced screen. */
internal object PlayerActivityRouter {
    private val observers = WeakHashMap<Activity, PlayerWindowObserver>()
    private val pages = WeakHashMap<Activity, NativePlayerPage>()
    private val launches = WeakHashMap<Activity, String>()
    private val closing = WeakHashMap<Activity, Boolean>()
    private val shields = WeakHashMap<Activity, View>()
    private val main = Handler(Looper.getMainLooper())

    fun onCreated(activity: Activity) {
        if (!rejectUnreplacedLaunch(activity)) observe(activity)
    }
    fun onResumed(activity: Activity) {
        if (activity is EnhancedPlayerActivity) return
        if (rejectUnreplacedLaunch(activity)) return
        safeHook("native player page") { MusicAppAdapters.onActivityReady(activity) }
        observe(activity)
    }

    fun onNativePageShown(activity: Activity, page: NativePlayerPage): Boolean {
        if (closing[activity] == true || activity.isDestroyed || activity.isFinishing) return false
        pages[activity] = page
        observe(activity)
        return launches.containsKey(activity)
    }

    fun onNativePageHidden(activity: Activity, identity: Any) {
        if (pages[activity]?.identity == identity) pages.remove(activity)
        closing.remove(activity)
    }

    private fun observe(activity: Activity) {
        if (activity is EnhancedPlayerActivity || activity.isFinishing || activity.isDestroyed) return
        if (MusicAppRegistry.find(activity.packageName) == null) return
        observers.getOrPut(activity) {
            PlayerWindowObserver(activity.window.decorView) { safeHook("player route update") { update(activity) } }
        }.start()
    }

    private fun update(source: Activity) {
        if (source.isFinishing || source.isDestroyed || closing[source] == true || launches.containsKey(source)) return
        val profile = MusicAppRegistry.find(source.packageName) ?: return
        val page = pages[source]
        if (!profile.isPlayerActivity(source.javaClass.name) && page == null) {
            CoverHomeAppearance.update(source)
            return
        }
        if (!hookEnabled(profile) || !CoverScreenDetector.isCoverScreen(source)) {
            MusicAppAdapters.onCoverPlayerUnavailable(source)
            return
        }
        if (profile.isHorizontalPlayerActivity(source.javaClass.name)) {
            source.finish()
            return
        }
        if (!PlayerActivityLaunchHook.ready) return
        val component = PlayerActivityLaunchHook.component(source) ?: return
        val owner = WeakReference(source)
        val nativeRoot = page?.nativeRoot ?: WeakReference(source.window.decorView as? ViewGroup)
        val token = UUID.randomUUID().toString()
        val session = PlayerActivitySession(
            windowTitle = profile.enhancedPlayerWindowTitle(component.className),
            createController = {
                val current = owner.get()?.takeUnless { it.isDestroyed || it.isFinishing }
                val root = nativeRoot.get()
                if (current != null && root != null) MusicAppAdapters.create(profile, current, root) else null
            },
            canShow = { activity -> hookEnabled(profile) && CoverScreenDetector.isCoverScreen(activity) },
            keepScreenOn = { module.getRemotePreferences(Prefs.NAME).getBoolean(Prefs.KEEP_COVER_SCREEN_ON, false) },
            onDismiss = {
                owner.get()?.let { activity ->
                    closing[activity] = true
                    if (page == null) { activity.finish(); true } else {
                        page.dismiss().also { accepted -> if (!accepted) closing.remove(activity) }
                    }
                } ?: true
            },
            onClosed = {
                owner.get()?.let { activity ->
                    if (launches[activity] == token) launches.remove(activity)
                    removeShield(activity)
                }
            },
            onLaunchFailed = {
                owner.get()?.let {
                    if (closing[it] != true) {
                        closing[it] = true
                        page?.onLaunchFailed?.let { restore -> main.post { safeHook("restore native player") { restore() } } }
                    }
                }
            },
        )
        launches[source] = token
        PlayerActivitySessions.put(token, session)
        val decor = source.window.decorView as ViewGroup
        shields[source] = View(source).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            decor.addView(this, ViewGroup.LayoutParams(-1, -1))
        }
        try {
            source.startActivity(Intent(PlayerActivitySessions.ACTION).setComponent(component)
                .putExtra(PlayerActivitySessions.EXTRA_SESSION, token))
            moduleInfo("Enhanced Activity requested; app=${profile.packageName}, slot=${component.className}")
            // A failed launch must not leave a permanent black native page or retain its session.
            main.postDelayed({
                if (PlayerActivitySessions.find(token) === session && session.activity.get() == null) {
                    session.failLaunch()
                    PlayerActivitySessions.remove(token)
                    moduleInfo("Enhanced Activity launch timed out; native page restored")
                }
            }, LAUNCH_TIMEOUT_MS)
        } catch (error: Exception) {
            session.failLaunch()
            PlayerActivitySessions.remove(token)
            throw error
        }
    }

    private fun rejectUnreplacedLaunch(activity: Activity): Boolean {
        if (activity is EnhancedPlayerActivity || activity.intent?.action != PlayerActivitySessions.ACTION) return false
        // A host Instrumentation replacement must never recursively launch more native pages.
        activity.intent.getStringExtra(PlayerActivitySessions.EXTRA_SESSION)?.let { token ->
            PlayerActivitySessions.find(token)?.failLaunch()
            PlayerActivitySessions.remove(token)
        }
        activity.finish()
        moduleInfo("Enhanced Activity factory was bypassed; native launch cancelled")
        return true
    }

    private fun removeShield(activity: Activity) {
        shields.remove(activity)?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    fun onPaused(activity: Activity) { observers[activity]?.stop() }
    fun onDestroyed(activity: Activity) {
        if (activity is EnhancedPlayerActivity) return
        observers.remove(activity)?.stop()
        launches.remove(activity)?.let { token ->
            PlayerActivitySessions.find(token)?.activity?.get()?.finish()
            PlayerActivitySessions.remove(token)
        }
        removeShield(activity)
        pages.remove(activity)
        closing.remove(activity)
        CoverHomeAppearance.onDestroyed(activity)
    }

    private const val LAUNCH_TIMEOUT_MS = 5_000L
}
