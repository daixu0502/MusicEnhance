package com.jaco.musicenhance.adapter.kugoulite

import android.app.Activity
import com.jaco.musicenhance.player.model.PlayerSnapshot
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class KugouLiteFavoriteSourceTest {
    private val song = PlayerSnapshot.Empty.copy(title = "Song", artist = "Artist")

    @Before fun reset() {
        Service.current = Song("hash", 1)
        Database.favorite = true
        Database.onRead = {}
        Delegate.calls.clear()
        Delegate.releases = 0
    }

    @Test fun readsByHashAndMixIdWithoutNativeViewTagsAndRejectsMismatchedSongs() = withSource { source ->
        assertEquals(KugouLiteFavoriteControl.State("1:hash", true), source.read(song))
        Database.favorite = false
        assertEquals(false, source.read(song)?.favorite)
        assertNull(source.read(song.copy(title = "Stale")))
        assertNull(source.read(song.copy(artist = "Other artist")))
        Database.onRead = { Service.current = Song("other", 2) }
        assertNull(source.read(song))
    }

    @Test fun nativeActionUsesProviderEvenWhenSourceActivityIsPausedAndHasNoLikeButton() = withSource { source ->
        val state = requireNotNull(source.read(song))
        assertTrue(source.toggle(state))
        assertEquals(listOf(true), Delegate.calls)
        assertEquals(1, Delegate.releases)
        Service.current = Song("new-recording", 3)
        assertFalse(source.toggle(state))
        assertEquals(1, Delegate.calls.size)
    }

    @Test fun aiLibraryAndMissingSongDoNotMasqueradeAsOrdinaryUnlikedSongs() = withSource { source ->
        Service.current = Song("hash", 1, ai = true)
        assertNull(source.read(song))
        Service.current = Song("hash", 1, timbre = true)
        assertNull(source.read(song))
        Service.current = null
        assertNull(source.read(song))
    }

    private fun withSource(block: (KugouLiteFavoriteSource) -> Unit) {
        val activity = Robolectric.buildActivity(HostActivity::class.java).create().start().resume().pause()
        try { block(KugouLiteFavoriteSource(activity.get())) } finally { activity.destroy() }
    }

    class HostActivity : Activity() {
        @Suppress("unused") private val api = PageApi()
        override fun getClassLoader(): ClassLoader = nativeLoader
    }
    class PageApi { @Suppress("unused") private val page: Page = ChildPage() }
    interface Page
    open class BasePage : Page { @Suppress("unused") private val provider: LandProvider = HostProvider() }
    class ChildPage : BasePage()
    interface Provider
    interface LandProvider : Provider
    class HostProvider : LandProvider
    interface ISong
    class Song(private val hash: String, private val mix: Long, val ai: Boolean = false, val timbre: Boolean = false) : ISong {
        fun getHashValue() = hash
        fun getMixId() = mix
        fun getTrackName() = "Song"
        fun getArtistName() = "Artist"
    }
    object Service {
        var current: Song? = null
        @JvmStatic fun s0() = current
    }
    object Database {
        var favorite = false
        var onRead: () -> Unit = {}
        @JvmStatic fun O(hash: String, mixId: Long): Boolean {
            assertEquals("hash", hash)
            assertEquals(1L, mixId)
            onRead()
            return favorite
        }
    }
    object Kinds {
        @JvmStatic fun j(song: ISong) = (song as Song).ai
        @JvmStatic fun o(song: ISong) = (song as Song).timbre
    }
    class Delegate(@Suppress("unused") provider: Provider) {
        fun u(favorite: Boolean) { calls += favorite }
        fun v() { releases++ }
        companion object {
            val calls = mutableListOf<Boolean>()
            var releases = 0
        }
    }
    companion object {
        private val nativeLoader = object : ClassLoader(KugouLiteFavoriteSourceTest::class.java.classLoader) {
            override fun loadClass(name: String): Class<*> = when (name) {
                "com.kugou.framework.service.util.PlaybackServiceUtil" -> Service::class.java
                "com.kugou.framework.service.entity.KGMusicWrapper" -> Song::class.java
                "com.kugou.common.utils.MyFavUtils" -> Database::class.java
                "com.kugou.android.common.entity.ISong" -> ISong::class.java
                "ra0.b" -> Kinds::class.java
                "com.kugou.android.app.player.subview.base.IProvider" -> Provider::class.java
                "com.kugou.android.app.player.song.delegate.SongFavDelegate" -> Delegate::class.java
                "com.kugou.android.app.player.land.p" -> PageApi::class.java
                "com.kugou.android.app.player.land.ILandPlayerPage" -> Page::class.java
                "com.kugou.android.app.player.land.ILandProvider" -> LandProvider::class.java
                else -> super.loadClass(name)
            }
        }
    }
}
