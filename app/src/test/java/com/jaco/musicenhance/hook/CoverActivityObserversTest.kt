package com.jaco.musicenhance.hook

import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], manifest = Config.NONE)
class CoverActivityObserversTest {
    private class Token : IBinder by Binder() {
        val recipients = mutableSetOf<IBinder.DeathRecipient>()
        var beforeLink: () -> Unit = {}
        private var alive = true
        override fun isBinderAlive() = alive
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { beforeLink(); recipients += recipient }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = recipients.remove(recipient)
        fun die() { alive = false; recipients.toList().forEach { it.binderDied() } }
    }

    private val component = ComponentName("com.kugou.android.lite", "com.kugou.android.app.MediaActivity")
    private val other = ComponentName("com.example", "com.example.Home")
    private fun resume(registry: CoverActivityObservers, token: IBinder, target: ComponentName = component) =
        registry.deliverNative(token, "activityResumed", Intent().setComponent(target)) { Unit }

    @Test fun refreshUsesRealIntentAndKeepsAnIndependentCopy() {
        val registry = CoverActivityObservers()
        val token = Token()
        val received = mutableListOf<Intent>()
        registry.register(token, 0) { received += Intent(it); it.component = other }
        assertEquals(0, registry.refresh(component, 0))
        val original = Intent("native-resume").setComponent(component).putExtra("native-extra", 12)
        registry.deliverNative(token, "activityResumed", original) { Unit }
        original.component = other
        assertEquals(1, registry.refresh(component, 0))
        assertEquals(1, registry.refresh(component, 0))
        assertEquals(2, received.size)
        assertTrue(received.all { it.component == component && it.getIntExtra("native-extra", 0) == 12 })
    }

    @Test fun newerForegroundEventDiscardsPendingRefreshForOldPage() {
        val registry = CoverActivityObservers()
        val token = Token()
        var calls = 0
        registry.register(token, 0) { calls++ }
        resume(registry, token)
        resume(registry, token, other)
        assertEquals(0, registry.refresh(component, 0))
        assertEquals(0, calls)
    }

    @Test fun lifecycleEndPreventsRefreshUntilTheNextResume() {
        for (event in listOf("activityPaused", "activityStopped", "activityDestroyed")) {
            val registry = CoverActivityObservers()
            val token = Token()
            registry.register(token, 0) { }
            resume(registry, token)
            registry.deliverNative(token, event, Intent().setComponent(component)) { Unit }
            assertEquals(0, registry.refresh(component, 0))
            resume(registry, token)
            assertEquals(1, registry.refresh(component, 0))
        }
    }

    @Test fun unrelatedLifecycleAndOtherUsersDoNotChangeThisObserversState() {
        val registry = CoverActivityObservers()
        val token = Token()
        registry.register(token, 10) { }
        resume(registry, token)
        registry.deliverNative(token, "activityDestroyed", Intent().setComponent(other)) { Unit }
        assertEquals(0, registry.refresh(component, 0))
        assertEquals(1, registry.refresh(component, 10))
    }

    @Test fun unregisterAndBinderDeathReleaseCallbacks() {
        val registry = CoverActivityObservers()
        val token = Token()
        registry.register(token, 0) { fail("Unregistered callback invoked") }
        resume(registry, token)
        registry.unregister(token)
        assertTrue(token.recipients.isEmpty())
        assertEquals(0, registry.refresh(component, 0))

        val replacement = Token()
        registry.register(replacement, 0) { fail("Dead callback invoked") }
        resume(registry, replacement)
        replacement.die()
        assertTrue(replacement.recipients.isEmpty())
        assertEquals(0, registry.refresh(component, 0))
    }

    @Test fun duplicateRegistrationKeepsLatestNativeEventAndOneDeathRecipient() {
        val registry = CoverActivityObservers()
        val token = Token()
        registry.register(token, 0) { }
        resume(registry, token)
        registry.register(token, 0) { fail("Duplicate observer must not replace registered callback") }
        assertEquals(1, token.recipients.size)
        assertEquals(1, registry.refresh(component, 0))
    }

    @Test fun unregisterDuringBinderRegistrationDoesNotLeakADeathRecipient() {
        val registry = CoverActivityObservers()
        val token = Token()
        token.beforeLink = { registry.unregister(token) }
        registry.register(token, 0) { fail("Cancelled registration invoked") }
        assertTrue(token.recipients.isEmpty())
        resume(registry, token)
        assertEquals(0, registry.refresh(component, 0))
    }

    @Test fun failedNativeDeliveryCannotBeReplayed() {
        val registry = CoverActivityObservers()
        val token = Token()
        registry.register(token, 0) { fail("Undelivered event replayed") }
        resume(registry, token)
        runCatching {
            registry.deliverNative(token, "activityResumed", Intent().setComponent(other)) { error("remote failure") }
        }
        assertEquals(0, registry.refresh(component, 0))
        assertEquals(0, registry.refresh(other, 0))
    }

    @Test fun unregisteredObserversKeepTheirNativeCallbackBehavior() {
        val registry = CoverActivityObservers()
        var calls = 0
        assertEquals(42, registry.deliverNative(Token(), "activityResumed", Intent()) { calls++; 42 })
        assertEquals(1, calls)
    }

    @Test fun replayCanPassThroughTheNativeHookWithoutFeedback() {
        val registry = CoverActivityObservers()
        val token = Token()
        var calls = 0
        registry.register(token, 0) { intent ->
            registry.deliverNative(token, "activityResumed", intent) { calls++ }
        }
        resume(registry, token)
        assertEquals(1, registry.refresh(component, 0))
        assertEquals(1, calls)
    }
}
