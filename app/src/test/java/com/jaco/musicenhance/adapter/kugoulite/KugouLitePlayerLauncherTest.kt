package com.jaco.musicenhance.adapter.kugoulite

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], manifest = Config.NONE)
class KugouLitePlayerLauncherTest {
    @Test fun rapidTapsLaunchOneHostPlayerAndKeepHomeInItsExistingTask() {
        Robolectric.buildActivity(Activity::class.java).setup().use { source ->
            val intents = mutableListOf<Intent>()
            val launcher = KugouLitePlayerLauncher({ 0L }) { activity, intent ->
                assertSame(source.get(), activity)
                intents += intent
            }
            repeat(4) { assertTrue(launcher.launch(source.get())) }
            assertEquals(1, intents.size)
            val intent = intents.single()
            assertEquals(KugouLiteMusicProfile.packageName, intent.component?.packageName)
            assertEquals(KugouLitePlayerLauncher.ACTIVITY_NAME, intent.component?.className)
            assertEquals(0, intent.flags) // Don't clear/rebuild the host's task or navigate home again.
            source.get().intent = intent
            assertTrue(KugouLitePlayerLauncher.isModuleLaunch(source.get()))
        }
    }

    @Test fun nativeLaunchDoesNotAcquireModuleOnlyWindowPolicies() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            controller.get().intent = Intent().setClassName(KugouLiteMusicProfile.packageName, KugouLitePlayerLauncher.ACTIVITY_NAME)
            assertFalse(KugouLitePlayerLauncher.isModuleLaunch(controller.get()))
        }
    }

    @Test fun failedStartCanImmediatelyFallBackAndRetry() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            var starts = 0
            val launcher = KugouLitePlayerLauncher({ 0L }) { _, _ ->
                if (++starts == 1) throw ActivityNotFoundException()
            }
            assertTrue(runCatching { launcher.launch(controller.get()) }.exceptionOrNull() is ActivityNotFoundException)
            assertTrue(launcher.launch(controller.get()))
            assertEquals(2, starts)
        }
    }

    @Test fun missingCreationCallbackDoesNotPermanentlyBlockPlayerEntry() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            var nowMs = 0L
            var starts = 0
            val launcher = KugouLitePlayerLauncher({ nowMs }) { _, _ -> starts++ }
            launcher.launch(controller.get())
            nowMs = 3_000L
            launcher.launch(controller.get())
            assertEquals(2, starts)
        }
    }

    @Test fun returningToHomeAllowsImmediateReentryAndOldDestroyDoesNotClearNewPlayer() {
        Robolectric.buildActivity(Activity::class.java).setup().use { source ->
            Robolectric.buildActivity(Activity::class.java).setup().use { old ->
                Robolectric.buildActivity(Activity::class.java).setup().use { next ->
                    var starts = 0
                    val launcher = KugouLitePlayerLauncher({ 0L }) { _, _ -> starts++ }
                    launcher.launch(source.get())
                    launcher.onCreated(old.get())
                    launcher.launch(source.get())
                    assertEquals(1, starts)
                    old.get().finish()
                    launcher.launch(source.get())
                    assertEquals(2, starts)
                    launcher.onCreated(next.get())
                    launcher.onDestroyed(old.get())
                    launcher.launch(source.get())
                    assertEquals(2, starts)
                    launcher.onDestroyed(next.get())
                    launcher.launch(source.get())
                    assertEquals(3, starts)
                }
            }
        }
    }

    @Test fun finishingHomeCannotLaunchAnotherPlayer() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val launcher = KugouLitePlayerLauncher({ 0L }) { _, _ -> fail("Unexpected launch") }
            controller.get().finish()
            assertFalse(launcher.launch(controller.get()))
        }
    }
}
