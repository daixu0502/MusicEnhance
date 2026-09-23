package com.jaco.musicenhance.player.artwork

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ArtworkLoaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun prefetchUsesDiskWithoutFillingBitmapMemoryAndPlaybackCanPromoteIt() {
        val cache = ArtworkDiskCache(temporary.newFolder())
        // Non-network scheme: a successful load proves that the saved image was used.
        val address = "file:///${UUID.randomUUID()}.png"
        val original = Bitmap.createBitmap(150, 150, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        cache.write(address, bytes)
        val prefetched = ArtworkLoader.load(address, { true }, cache, keepInMemory = false)
        assertNotNull(prefetched)
        assertEquals(Color.BLUE, prefetched!!.getPixel(0, 0))
        assertNull(ArtworkLoader.cached(address))
        val displayed = ArtworkLoader.load(address, { true }, cache)
        assertEquals(Color.BLUE, displayed!!.getPixel(0, 0))
        assertSame(displayed, ArtworkLoader.cached(address))
    }

    @Test fun corruptDiskDataAndCancelledLoadsDoNotProduceArtwork() {
        val cache = ArtworkDiskCache(temporary.newFolder())
        val address = "file:///${UUID.randomUUID()}.png"
        cache.write(address, byteArrayOf(1, 2, 3))
        assertNull(ArtworkLoader.load(address, { false }, cache))
        assertNotNull(cache.read(address)) // Cancellation must not invalidate a cache entry.
        assertNull(ArtworkLoader.load(address, { true }, cache))
        assertFalse(cache.contains(address))
    }
}
