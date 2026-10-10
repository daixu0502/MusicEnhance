package com.jaco.musicenhance.hook

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.jaco.musicenhance.Prefs
import com.jaco.musicenhance.adapter.MusicAppAdapters
import com.jaco.musicenhance.adapter.MusicAppRegistry
import com.jaco.musicenhance.adapter.NativePlayerPage
import com.jaco.musicenhance.device.CoverHomeAppearance
import com.jaco.musicenhance.device.CoverScreenDetector
import com.jaco.musicenhance.player.EnhancedPlayerActivity
import com.jaco.musicenhance.player.PlayerActivitySession
import com.jaco.musicenhance.player.PlayerActivitySessions
import com.jaco.musicenhance.player.PlayerWindowObserver
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap

/** Intercepts navigation before host page creation; sessions belong to the enhanced Activity. */
internal object PlayerActivityRouter {
    private val observers = WeakHashMap<Activity, PlayerWindowObserver>()
    private val pages = WeakHashMap<Activity, NativePlayerPage>()
    private val launches = WeakHashMap<Activity, String>()
    private val closing = WeakHashMap<Activity, Boolean>()
    private val nativeFallbacks = WeakHashMap<Activity, Boolean>()
    private var replayingNavigation = false
    private val main = Handler(Looper.getMainLooper())
    private const val NATIVE_FALLBACK = "com.jaco.musicenhance.NATIVE_FALLBACK"

    fun onLaunchRequested(source: Activity?, intent: Intent?): Boolean {
        if (source == null || intent == null || intent.action == PlayerActivitySessions.ACTION ||
            intent.getBooleanExtra(NATIVE_FALLBACK, false)) return false
        val profile = MusicAppRegistry.find(source.packageName) ?: return false
        val target = intent.component ?: intent.resolveActivity(source.packageManager) ?: return false
        if (target.packageName != profile.packageName || !profile.isPlayerActivity(target.className) ||
            profile.isHorizontalPlayerActivity(target.className)) return false
        val owner = WeakReference(source)
        val original = Intent(intent).putExtra(NATIVE_FALLBACK, true)
        return launch(source, null) {
            owner.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.startActivity(original)
        }
    }

    /** Native fragment navigators call this instead of constructing a hidden player Activity. */
    fun launch(source: Activity, restore: (() -> Unit)? = null): Boolean {
        if (replayingNavigation) return false
        nativeFallbacks.remove(source)
        return launch(source, null, restore)
    }

    fun isNativeFallback(activity: Activity): Boolean = replayingNavigation ||
        nativeFallbacks[activity] == true || activity.intent?.getBooleanExtra(NATIVE_FALLBACK, false) == true

    fun onCreated(activity: Activity) {
        if (!rejectUnreplacedLaunch(activity)) observe(activity)
    }

    fun onResumed(activity: Activity) {
        if (activity is EnhancedPlayerActivity || isNativeFallback(activity) || rejectUnreplacedLaunch(activity)) return
        safeHook("native player page") { MusicAppAdapters.onActivityReady(activity) }
        observe(activity)
    }

    fun onNativePageShown(activity: Activity, page: NativePlayerPage): Boolean {
        if (isNativeFallback(activity) || closing[activity] == true || activity.isDestroyed || activity.isFinishing) return false
        pages[activity] = page
        return launch(activity, page, page.onLaunchFailed)
    }

    fun onNativePageHidden(activity: Activity, identity: Any) {
        if (pages[activity]?.identity == identity) pages.remove(activity)
        closing.remove(activity)
    }

    private fun observe(activity: Activity) {
        if (activity is EnhancedPlayerActivity || activity.isFinishing || activity.isDestroyed ||
            isNativeFallback(activity)) return
        if (MusicAppRegistry.find(activity.packageName) == null) return
        observers.getOrPut(activity) {
            PlayerWindowObserver(activity.window.decorView) { safeHook("player route update") { update(activity) } }
        }.start()
    }

    private fun update(source: Activity) {
        if (source.isFinishing || source.isDestroyed || isNativeFallback(source) || closing[source] == true || launches.containsKey(source)) return
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
        if (profile.isHorizontalPlayerActivity(source.javaClass.name)) { source.finish(); return }
        // Restored Activities and an inner-screen player folded onto the cover already exist.
        // Collapse them before making the enhanced window transparent; never use them for data.
        val owner = WeakReference(source)
        launch(source, page ?: NativePlayerPage(System.identityHashCode(source), dismiss = {
            owner.get()?.let { activity ->
                activity.window.exitTransition = null
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
                activity.finish()
            }
            true
        }), page?.onLaunchFailed)
    }

    private fun launch(source: Activity, page: NativePlayerPage?, restore: (() -> Unit)?): Boolean {
        if (source is EnhancedPlayerActivity || source.isFinishing || source.isDestroyed) return false
        val profile = MusicAppRegistry.find(source.packageName) ?: return false
        if (!hookEnabled(profile) || !CoverScreenDetector.isCoverScreen(source) || !PlayerActivityLaunchHook.ready) return false
        if (launches.containsKey(source)) return true
        val component = PlayerActivityLaunchHook.component(source) ?: return false
        val owner = WeakReference(source)
        val token = UUID.randomUUID().toString()
        lateinit var session: PlayerActivitySession
        val launchTimeout = Runnable {
            if (PlayerActivitySessions.find(token) === session && session.activity.get() == null) {
                session.failLaunch()
                PlayerActivitySessions.remove(token)
                moduleInfo("Enhanced Activity launch timed out; native navigation restored")
            }
        }
        session = PlayerActivitySession(
            windowTitle = profile.enhancedPlayerWindowTitle(component.className),
            createController = { enhanced -> MusicAppAdapters.create(profile, enhanced) },
            canShow = { activity -> hookEnabled(profile) && CoverScreenDetector.isCoverScreen(activity) },
            keepScreenOn = { module.getRemotePreferences(Prefs.NAME).getBoolean(Prefs.KEEP_COVER_SCREEN_ON, false) },
            onDismiss = {
                if (page == null) true else {
                    owner.get()?.let { closing[it] = true }
                    page.dismiss().also { accepted -> if (!accepted) owner.get()?.let(closing::remove) }
                }
            },
            prepareBackground = { session.dismiss() },
            onNavigationReleased = {
                main.removeCallbacks(launchTimeout)
                owner.get()?.let { activity ->
                    if (launches[activity] == token) {
                        launches.remove(activity)
                        // Repeated visibility callbacks may replace the page object while the
                        // same launch is active. Its token owns all source-page routing state.
                        pages.remove(activity)
                        closing.remove(activity)
                    }
                }
            },
            onLaunchFailed = {
                if (restore != null) main.post {
                    val activity = owner.get() ?: return@post
                    if (activity.isFinishing || activity.isDestroyed) return@post
                    nativeFallbacks[activity] = true
                    replayingNavigation = true
                    try { safeHook("restore native navigation", restore) }
                    finally { replayingNavigation = false }
                }
            },
        )
        launches[source] = token
        PlayerActivitySessions.put(token, session)
        try {
            source.startActivity(Intent(PlayerActivitySessions.ACTION).setComponent(component)
                .putExtra(PlayerActivitySessions.EXTRA_SESSION, token),
                ActivityOptions.makeCustomAnimation(source, 0, 0).toBundle())
            moduleInfo("Direct enhanced Activity requested; app=${profile.packageName}, slot=${component.className}, restoredPage=${page != null}")
            if (launches[source] == token) main.postDelayed(launchTimeout, LAUNCH_TIMEOUT_MS)
            return true
        } catch (error: Exception) {
            // The caller has not swallowed native navigation yet. Let it proceed exactly once.
            launches.remove(source)
            PlayerActivitySessions.remove(token)
            moduleInfo("Direct enhanced launch failed: $error")
            return false
        }
    }

    private fun rejectUnreplacedLaunch(activity: Activity): Boolean {
        if (activity is EnhancedPlayerActivity || activity.intent?.action != PlayerActivitySessions.ACTION) return false
        activity.intent.getStringExtra(PlayerActivitySessions.EXTRA_SESSION)?.let { token ->
            PlayerActivitySessions.find(token)?.failLaunch()
            PlayerActivitySessions.remove(token)
        }
        activity.finish()
        moduleInfo("Enhanced Activity factory was bypassed; native launch cancelled")
        return true
    }

    fun onPaused(activity: Activity) { observers[activity]?.stop() }

    fun onDestroyed(activity: Activity) {
        if (activity is EnhancedPlayerActivity) return
        observers.remove(activity)?.stop()
        // Closing the old native page must not destroy an independent enhanced session.
        launches.remove(activity)
        pages.remove(activity)
        closing.remove(activity)
        nativeFallbacks.remove(activity)
        CoverHomeAppearance.onDestroyed(activity)
    }

    private const val LAUNCH_TIMEOUT_MS = 5_000L
}
