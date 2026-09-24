package com.jaco.musicenhance.adapter.apple

import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.model.RepeatMode
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ApplePlaybackSourceTest {
    private lateinit var browser: Browser
    private lateinit var source: ApplePlaybackSource
    private var player = PlayerSnapshot.Empty

    @Before fun setup() {
        browser = Browser()
        Holder.f = browser
        source = ApplePlaybackSource(nativeLoader)
        player = snapshot(browser.metadata.song)
        Library.ready = true
        StarActions.supported = true
        StarActions.requests.clear()
        StarActions.items.clear()
        StarActions.deferred = false
        StarActions.queries.clear()
        StarActions.actionItem = null
    }

    @Test fun readsProcessMetadataWithoutConstructingOrResumingNativePlayer() {
        assertSame(browser.metadata.song, source.item(player))
        val replacement = browser.metadata.song.copy(likeState = 2)
        browser.metadata = Metadata(replacement)
        assertEquals(true, ApplePlaybackSource.favorite(source.item(player)))
        assertSame(replacement, source.item(player))
        assertNull(source.item(player.copy(artist = "Other artist")))
        assertNull(source.artwork)
        assertNull(source.item(player.copy(album = "Another recording")))
    }

    @Test fun copiesOnlyThreeNeighboursInEachDirectionAndInvalidatesOnTrackChange() {
        source.item(player)
        assertEquals(listOf("3", "2", "1", "5", "6", "7"), source.artwork!!.neighbours.map { it.id })
        assertEquals(7, browser.timeline.reads)
        source.item(player)
        assertEquals(7, browser.timeline.reads)
        val artwork = AppleArtworkSource { source.artwork }
        val old = artwork.currentSong(player)!!
        browser.index = 5
        browser.metadata = Metadata(browser.timeline.songs[5])
        assertNull(source.item(player))
        assertFalse(artwork.isCurrentSong(old))
        player = snapshot(browser.metadata.song)
        source.item(player)
        assertTrue(artwork.neighbours(old).isEmpty())
        source.release()
        assertNull(artwork.currentSong(player))
    }

    @Test fun queueUsesNativeShuffleOrderAndDoesNotPredictUnloadedTracks() {
        browser.shuffled = true
        browser.timeline.order = listOf(0, 2, 6, 4, 1, 7, 5, 8, 3)
        source.item(player)
        assertEquals(listOf("6", "2", "9", "1", "7", "5"), source.artwork!!.neighbours.map { it.id })
        browser.index = 0
        browser.metadata = Metadata(browser.timeline.songs[0])
        player = snapshot(browser.metadata.song)
        source.item(player)
        assertEquals(listOf("2", "6", "4"), source.artwork!!.neighbours.map { it.id })
    }

    @Test fun queueMismatchStillProvidesCurrentCoverButNeverPrefetchesWrongQueue() {
        browser.metadata = Metadata(browser.metadata.song.copy(queueId = 999))
        source.item(player)
        assertNotNull(source.artwork?.current)
        assertTrue(source.artwork!!.neighbours.isEmpty())
        Holder.f = null
        assertNull(source.item(player))
        assertNull(source.artwork)
    }

    @Test fun repeatUsesNativeModesAndExitsShuffleWithoutPopup() {
        val controls = AppleControlSource(source) { player }
        assertEquals(RepeatMode.SEQUENTIAL, controls.controlState().repeatMode)
        assertTrue(controls.cycleRepeat())
        assertEquals(RepeatMode.LIST_LOOP, controls.controlState().repeatMode)
        assertTrue(controls.cycleRepeat())
        assertEquals(RepeatMode.SINGLE_LOOP, controls.controlState().repeatMode)
        assertTrue(controls.cycleRepeat())
        assertEquals(RepeatMode.SHUFFLE, controls.controlState().repeatMode)
        assertEquals(0, browser.repeat)
        assertTrue(controls.cycleRepeat())
        assertFalse(browser.shuffled)
        assertEquals(RepeatMode.SEQUENTIAL, controls.controlState().repeatMode)
        browser.commandsAvailable = false
        assertEquals(RepeatMode.UNKNOWN, controls.controlState().repeatMode)
        assertFalse(controls.cycleRepeat())
    }

    @Test fun favoritesUseNativeStarOperationAndOnlyConfirmedStateChangesTheIcon() {
        val controls = AppleControlSource(source) { player }
        assertEquals(false, controls.controlState().favorite)
        assertTrue(controls.toggleFavorite())
        assertEquals(listOf(2), StarActions.requests)
        assertTrue(controls.controlState().favoritePending)
        assertFalse(controls.toggleFavorite())
        assertEquals(false, controls.controlState().favorite)
        StarActions.confirm(2)
        assertEquals(true, controls.controlState().favorite)
        assertFalse(controls.controlState().favoritePending)
        assertTrue(controls.toggleFavorite())
        assertEquals(listOf(2, 1), StarActions.requests)
        source.release()
        assertFalse(source.favoritePending)
    }

    @Test fun rejectedFavoriteCanRetryAndUnknownStateNeverMasqueradesAsUnliked() {
        val controls = AppleControlSource(source) { player }
        assertTrue(controls.toggleFavorite())
        assertEquals(false, controls.controlState().favorite)
        assertFalse(controls.toggleFavorite())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        assertTrue(controls.toggleFavorite())
        StarActions.items.getValue(browser.metadata.song.id).likeState = 0
        StarActions.actionItem!!.likeState = 0
        assertNull(controls.controlState().favorite)
        assertFalse(controls.toggleFavorite())
        player = player.copy(title = "next song")
        assertNull(controls.controlState().favorite)
    }

    @Test fun unavailableAccountAndUnsupportedItemsDisableFavoriteWithoutBreakingRepeat() {
        val controls = AppleControlSource(source) { player }
        Library.ready = false
        assertNull(controls.controlState().favorite)
        assertFalse(controls.toggleFavorite())
        assertTrue(controls.cycleRepeat())
        Library.ready = true
        StarActions.supported = false
        assertNull(controls.controlState().favorite)
        assertFalse(controls.toggleFavorite())
        assertTrue(StarActions.requests.isEmpty())
    }

    @Test fun reopeningReadsLibraryFavoriteInsteadOfStalePlaybackMetadata() {
        val controls = AppleControlSource(source) { player }
        assertEquals(false, controls.controlState().favorite)
        assertTrue(controls.toggleFavorite())
        StarActions.confirm(2)
        assertEquals(true, controls.controlState().favorite)
        assertEquals(1, browser.metadata.song.likeState)
        source.release()
        source = ApplePlaybackSource(nativeLoader)
        assertEquals(true, AppleControlSource(source) { player }.controlState().favorite)
    }

    @Test fun lateLibraryResultCannotReplaceNextSongOrReleasedState() {
        StarActions.deferred = true
        val controls = AppleControlSource(source) { player }
        assertNull(controls.controlState().favorite)
        val old = StarActions.queries.single()
        browser.metadata = Metadata(Song("22", likeState = 2))
        player = snapshot(browser.metadata.song)
        assertNull(controls.controlState().favorite)
        assertTrue(old.disposed)
        old.complete()
        assertNull(controls.controlState().favorite)
        StarActions.queries.last().complete()
        assertEquals(true, controls.controlState().favorite)
        source.release()
        assertTrue(StarActions.queries.last().disposed)
    }

    @Test fun delayedNativeSuccessSurvivesStaleDatabaseRefreshAndCancelsOldQuery() {
        val controls = AppleControlSource(source) { player }
        assertEquals(false, controls.controlState().favorite)
        assertTrue(controls.toggleFavorite())
        ShadowSystemClock.advanceBy(Duration.ofMillis(200))
        assertEquals(false, controls.controlState().favorite)
        assertEquals(2, StarActions.queries.size)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        assertEquals(false, controls.controlState().favorite)
        assertFalse(controls.toggleFavorite())
        StarActions.deferred = true
        ShadowSystemClock.advanceBy(Duration.ofMillis(200))
        assertEquals(false, controls.controlState().favorite)
        val staleRead = StarActions.queries.last()
        StarActions.actionItem!!.likeState = 2
        assertEquals(true, controls.controlState().favorite)
        assertTrue(staleRead.disposed)
        staleRead.complete()
        assertEquals(true, controls.controlState().favorite)
        source.release()
    }

    @Test fun unavailableShuffleCommandDoesNotPartiallyChangeRepeatMode() {
        val controls = AppleControlSource(source) { player }
        browser.repeat = 1
        browser.shuffleAvailable = false
        assertFalse(controls.cycleRepeat())
        assertEquals(1, browser.repeat)
        assertFalse(browser.shuffled)
    }

    @Test fun shortLoopQueueDeduplicatesNeighboursAndExcludesCurrentSong() {
        browser.timeline = Timeline(listOf(Song("9"), Song("1")))
        browser.index = 0
        browser.repeat = 2
        browser.metadata = Metadata(browser.timeline.songs[0])
        player = snapshot(browser.metadata.song)
        source.item(player)
        assertEquals(listOf("1"), source.artwork!!.neighbours.map { it.id })
    }

    private fun snapshot(song: Song) = PlayerSnapshot.Empty.copy(title = song.title, artist = song.artistName, album = song.collectionName)

    // Contract doubles mirror the verified 6.5.2 DEX names. No native player view is required.
    // Host API names are invoked reflectively by the adapter under test.
    @Suppress("unused")
    data class Song(val id: String, val queueId: Long = id.toLong(), var likeState: Int = 1) {
        val title get() = "Song $id"
        val artistName get() = "Artist"
        val collectionName get() = "Album"
        val imageUrl get() = "https://is1-ssl.mzstatic.com/image/thumb/test/$id/300x300bb.jpg"
    }
    class Metadata(val song: Song)
    @Suppress("unused")
    class MediaItem(@JvmField val d: Metadata)
    class Window { @JvmField var c: MediaItem? = null }
    object Holder { @JvmField var f: Browser? = null }
    @Suppress("unused")
    object Converter { @JvmStatic fun b(metadata: Metadata) = metadata.song }
    @Suppress("unused")
    enum class Like { None, Liked }
    @Suppress("unused")
    object Library {
        var ready = true
        @JvmStatic fun W() = this
        fun isReady() = ready
    }
    @Suppress("unused")
    object StarActions {
        var supported = true
        val requests = mutableListOf<Int>()
        val items = mutableMapOf<String, Song>()
        var deferred = false
        var actionItem: Song? = null
        val queries = mutableListOf<Single>()
        @JvmStatic fun w(item: Song) = Single(items.getOrPut(item.id) { item.copy() }.copy()).also { queries += it }
        fun confirm(state: Int) {
            val item = actionItem!!
            item.likeState = state
            items.getValue(item.id).likeState = state
        }
        @JvmStatic fun r(item: Song) = supported && item.id.isNotEmpty()
        @JvmStatic fun u(item: Song, target: Like, state: Int, feedback: Boolean) {
            assertTrue(feedback)
            assertEquals(if (target == Like.Liked) 2 else 1, state)
            assertNotEquals(item.likeState, state)
            requests += state
            actionItem = item
        }
    }
    fun interface Consumer { fun accept(value: Any?) }
    interface Disposable { fun f() }
    class Scheduler
    @Suppress("unused")
    object Schedulers { @JvmStatic fun a() = Scheduler() }
    @Suppress("unused")
    class Optional(private val item: Song?) {
        fun a() = item
        fun b() = item == null
    }
    @Suppress("unused")
    class Single(private val song: Song) : Disposable {
        var disposed = false
        private var success: Consumer? = null
        fun i(@Suppress("UNUSED_PARAMETER") scheduler: Scheduler): Single = this
        fun k(success: Consumer, @Suppress("UNUSED_PARAMETER") failure: Consumer): Disposable {
            this.success = success
            if (!StarActions.deferred) complete()
            return this
        }
        fun complete() { success!!.accept(Optional(song)) }
        override fun f() { disposed = true }
    }
    @Suppress("unused")
    class Browser {
        var timeline = Timeline(listOf(Song("9")) + (1..8).map { Song("$it") })
        var index = 4
        var metadata = Metadata(timeline.songs[index])
        var repeat = 0
        var shuffled = false
        var commandsAvailable = true
        var shuffleAvailable = true
        fun m0() = metadata
        fun getRepeatMode() = repeat
        fun getShuffleModeEnabled() = shuffled
        fun setRepeatMode(mode: Int) { repeat = mode }
        fun setShuffleModeEnabled(enabled: Boolean) { shuffled = enabled }
        fun d(command: Int) = commandsAvailable && (command == 15 || (command == 14 && shuffleAvailable))
        fun getCurrentTimeline() = timeline
        fun e0() = index
    }
    @Suppress("unused")
    class Timeline(val songs: List<Song>) {
        var reads = 0
        var order = songs.indices.toList()
        fun i() = songs.size
        fun h(index: Int, window: Window): Window {
            reads++
            window.c = MediaItem(Metadata(songs[index]))
            return window
        }
        fun d(index: Int, repeat: Int, shuffle: Boolean) = step(index, repeat, shuffle, 1)
        fun g(index: Int, repeat: Int, shuffle: Boolean) = step(index, repeat, shuffle, -1)
        private fun step(index: Int, repeat: Int, shuffle: Boolean, direction: Int): Int {
            val order = if (shuffle) order else songs.indices.toList()
            val next = order.indexOf(index) + direction
            return if (next in order.indices) order[next] else if (repeat == 2) order[Math.floorMod(next, order.size)] else -1
        }
    }
    private val nativeLoader = object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String): Class<*> = when (name) {
            "c9.a" -> Holder::class.java
            "E3.F" -> Browser::class.java
            "B8.s" -> StarActions::class.java
            "Sf.o" -> Single::class.java
            "Sf.n" -> Scheduler::class.java
            "Wf.e" -> Consumer::class.java
            "Tf.b" -> Disposable::class.java
            "Rf.a" -> Schedulers::class.java
            "com.apple.android.medialibrary.library.a" -> Library::class.java
            "com.apple.android.medialibrary.library.MediaLibrary\$f" -> Like::class.java
            "com.apple.android.music.model.BaseContentItem" -> Song::class.java
            "v3.v" -> Metadata::class.java
            "v3.J" -> Timeline::class.java
            "v3.J\$d" -> Window::class.java
            "v3.t" -> MediaItem::class.java
            "com.apple.android.music.player.O" -> Converter::class.java
            else -> super.loadClass(name)
        }
    }
}
