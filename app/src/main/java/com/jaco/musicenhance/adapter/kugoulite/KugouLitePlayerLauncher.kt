package com.jaco.musicenhance.adapter.kugoulite

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import java.lang.ref.WeakReference

/** Launches a real host Activity so HyperOS sees separate home/player window policies. */
internal class KugouLitePlayerLauncher(
    private val elapsedTimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val startActivity: (Activity, Intent) -> Unit = { source, intent -> source.startActivity(intent) },
) {
    private var pendingSinceMs: Long? = null
    private var player = WeakReference<Activity>(null)

    fun launch(source: Activity): Boolean {
        if (source.isFinishing || source.isDestroyed) return false
        if (player.get()?.let { !it.isFinishing && !it.isDestroyed } == true) return true
        val nowMs = elapsedTimeMs()
        if (pendingSinceMs?.let { nowMs - it < LAUNCH_TIMEOUT_MS } == true) return true
        pendingSinceMs = nowMs
        try {
            startActivity(source, Intent().setClassName(KugouLitePlayerProfile.packageName, ACTIVITY_NAME)
                .putExtra(EXTRA_COVER_PLAYER, true))
        } catch (error: Exception) {
            pendingSinceMs = null
            throw error // The navigation hook logs the failure and proceeds to the native page.
        }
        return true
    }

    fun onCreated(activity: Activity) {
        pendingSinceMs = null
        player = WeakReference(activity)
    }

    fun onDestroyed(activity: Activity) {
        if (player.get() === activity) player.clear()
    }

    companion object {
        const val ACTIVITY_NAME = "com.kugou.android.app.player.land.LandPlayerActivity"
        const val FRAGMENT_NAME = "com.kugou.android.app.player.YoungPlayerFragment"
        private const val EXTRA_COVER_PLAYER = "com.jaco.musicenhance.KUGOU_LITE_COVER_PLAYER"
        private const val LAUNCH_TIMEOUT_MS = 2_000L

        fun isModuleLaunch(activity: Activity): Boolean =
            activity.intent?.component?.packageName == KugouLitePlayerProfile.packageName &&
                activity.intent?.component?.className == ACTIVITY_NAME &&
                activity.intent?.getBooleanExtra(EXTRA_COVER_PLAYER, false) == true
    }
}
