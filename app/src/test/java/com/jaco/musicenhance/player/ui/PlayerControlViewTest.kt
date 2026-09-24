package com.jaco.musicenhance.player.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PlayerControlViewTest {
    @Test fun pendingFeedbackKeepsConfirmedStateAndStopsWhenHiddenOrDetached() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val button = PlayerControlView(activity.get(), PlayerControlView.Kind.FAVORITE)
            var clicks = 0
            button.setOnClickListener { clicks++ }
            activity.get().setContentView(button)
            shadowOf(Looper.getMainLooper()).idle()
            // Robolectric's attached window remains GONE until ViewRoot's visibility is delivered.
            val attachInfo = ReflectionHelpers.getField<Any>(button, "mAttachInfo")
            ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
            button.dispatchWindowVisibilityChanged(View.VISIBLE)
            button.active = false
            button.pending = true
            fun animation() = ReflectionHelpers.getField<ValueAnimator?>(button, "pendingAnimator")
            assertNotNull(animation())
            assertFalse(button.active)
            assertFalse(button.performClick())
            assertEquals(0, clicks)
            button.visibility = View.GONE
            assertNull(animation())
            button.visibility = View.VISIBLE
            assertNotNull(animation())
            button.pending = false
            assertNull(animation())
            button.performClick()
            assertEquals(1, clicks)
            button.pending = true
            activity.get().setContentView(View(activity.get()))
            assertNull(animation())
        } finally { activity.pause().stop().destroy() }
    }
}
