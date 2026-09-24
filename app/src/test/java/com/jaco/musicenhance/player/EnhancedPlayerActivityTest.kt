package com.jaco.musicenhance.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.View
import android.view.WindowManager
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.ui.CoverPlayerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], manifest = Config.NONE)
class EnhancedPlayerActivityTest {
    @Test fun screenOwnsItsWindowAndReleasesNativeSessionOnFinish() {
        var closed = 0
        var dismissed = 0
        var released = 0
        var listeners = 0
        val token = "activity-test"
        val data = object : PlayerDataSource {
            override fun snapshot() = PlayerSnapshot.Empty
            override fun addListener(listener: (PlayerSnapshot) -> Unit) { listeners++ }
            override fun removeListener(listener: (PlayerSnapshot) -> Unit) { listeners-- }
            override fun bassLevel() = 0f
        }
        PlayerActivitySessions.put(token, PlayerActivitySession(
            "MusicEnhance:test/Player", {
                EnhancedPlayerController(PlayerSession("Test", data, PlayerActions({}, {}, {}, {}, {}), onRelease = { released++ }))
            }, { true }, { false }, { dismissed++; true }, { closed++ },
        ))
        val controller = Robolectric.buildActivity(EnhancedPlayerActivity::class.java,
            Intent(PlayerActivitySessions.ACTION).putExtra(PlayerActivitySessions.EXTRA_SESSION, token)).setup().visible()
        val activity = controller.get()
        val player = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        assertTrue(player is CoverPlayerView)
        // Robolectric leaves attach-info window visibility GONE even after visible().
        val attachInfo = ReflectionHelpers.getField<Any>(player, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
        player.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, activity.requestedOrientation)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS, activity.window.attributes.layoutInDisplayCutoutMode)
        assertEquals("MusicEnhance:test/Player", activity.window.attributes.title.toString())
        assertEquals(1, listeners)
        activity.finish()
        controller.pause().stop().destroy()
        assertEquals(0, listeners)
        assertEquals(1, released)
        assertEquals(1, dismissed)
        assertEquals(1, closed)
        assertNull(PlayerActivitySessions.find(token))
    }

    @Test fun lostProcessSessionClosesWithoutShowingAHostSettingsPage() {
        val controller = Robolectric.buildActivity(EnhancedPlayerActivity::class.java,
            Intent(PlayerActivitySessions.ACTION).putExtra(PlayerActivitySessions.EXTRA_SESSION, "lost-session")).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun activityRecreationReusesHandoffButRecreatesAndReleasesProviders() {
        var created = 0
        var released = 0
        var dismissed = 0
        val data = object : PlayerDataSource {
            override fun snapshot() = PlayerSnapshot.Empty
            override fun addListener(listener: (PlayerSnapshot) -> Unit) = Unit
            override fun removeListener(listener: (PlayerSnapshot) -> Unit) = Unit
            override fun bassLevel() = 0f
        }
        val session = PlayerActivitySession("MusicEnhance:test/Player", {
            created++
            EnhancedPlayerController(PlayerSession("Test", data, PlayerActions({}, {}, {}, {}, {}), onRelease = { released++ }))
        }, { true }, { false }, { dismissed++; true }, {})
        PlayerActivitySessions.put("recreate", session)
        val controller = Robolectric.buildActivity(EnhancedPlayerActivity::class.java,
            Intent(PlayerActivitySessions.ACTION).putExtra(PlayerActivitySessions.EXTRA_SESSION, "recreate")).setup().visible()
        controller.recreate().visible()
        assertEquals(2, created)
        assertEquals(1, released)
        assertEquals(0, dismissed)
        assertSame(session, PlayerActivitySessions.find("recreate"))
        controller.pause().stop().destroy()
        assertEquals(2, released)
        assertEquals(1, dismissed)
        assertNull(PlayerActivitySessions.find("recreate"))
    }

    @Test fun tokenCleanupAndNativeDismissAreIdempotent() {
        var closed = 0
        var dismissed = 0
        val session = PlayerActivitySession("test", { null }, { true }, { false }, { dismissed++; true }, { closed++ })
        PlayerActivitySessions.put("close-test", session)
        session.dismiss(); session.dismiss()
        PlayerActivitySessions.remove("close-test"); PlayerActivitySessions.remove("close-test")
        assertEquals(1, dismissed)
        assertEquals(1, closed)
    }

    @Test fun failedControllerReturnsToNativePlayerWithoutCollapsingFallback() {
        var restored = 0
        var dismissed = 0
        var closed = 0
        val token = "failed-controller"
        val session = PlayerActivitySession("test", { null }, { true }, { false },
            { dismissed++; true }, { closed++ }, { restored++ })
        PlayerActivitySessions.put(token, session)
        val activity = Robolectric.buildActivity(EnhancedPlayerActivity::class.java,
            Intent(PlayerActivitySessions.ACTION).putExtra(PlayerActivitySessions.EXTRA_SESSION, token)).create()
        assertTrue(activity.get().isFinishing)
        session.failLaunch()
        activity.destroy()
        assertEquals(1, restored)
        assertEquals(0, dismissed)
        assertEquals(1, closed)
        assertNull(PlayerActivitySessions.find(token))
    }

    @Test fun displayChangeDuringLaunchRestoresNativeEntryAndReleasesHandoff() {
        var restored = 0
        var dismissed = 0
        val token = "display-change"
        PlayerActivitySessions.put(token, PlayerActivitySession("test", { error("must not create") }, { false }, { false },
            { dismissed++; true }, {}, { restored++ }))
        val activity = Robolectric.buildActivity(EnhancedPlayerActivity::class.java,
            Intent(PlayerActivitySessions.ACTION).putExtra(PlayerActivitySessions.EXTRA_SESSION, token)).create()
        assertTrue(activity.get().isFinishing)
        activity.destroy()
        assertEquals(1, restored)
        assertEquals(0, dismissed)
        assertNull(PlayerActivitySessions.find(token))
    }

    @Test fun refusedNativeDismissCanBeRetriedWithoutClosingItsSession() {
        var accepted = false
        var attempts = 0
        val session = PlayerActivitySession("test", { null }, { true }, { false }, { attempts++; accepted }, {})
        assertFalse(session.dismiss())
        accepted = true
        assertTrue(session.dismiss())
        assertTrue(session.dismiss())
        assertEquals(2, attempts)
    }
}
