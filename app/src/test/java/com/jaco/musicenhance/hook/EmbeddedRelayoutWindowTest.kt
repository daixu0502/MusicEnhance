package com.jaco.musicenhance.hook

import android.os.Binder
import android.os.IInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], manifest = Config.NONE)
class EmbeddedRelayoutWindowTest {
    @Test fun resolvesModernBinderAndOlderIWindowSignatures() {
        val token = Binder()
        val window = Any()
        val windows = mapOf(token to window)
        assertSame(window, SystemCoverHook.resolveRelayoutWindow(listOf(null, Binder(), token, 1), windows))
        val iWindow = IInterface { token }
        assertSame(window, SystemCoverHook.resolveRelayoutWindow(listOf(iWindow, 1), windows))
        assertNull(SystemCoverHook.resolveRelayoutWindow(listOf(Binder(), 1), windows))
    }
}
