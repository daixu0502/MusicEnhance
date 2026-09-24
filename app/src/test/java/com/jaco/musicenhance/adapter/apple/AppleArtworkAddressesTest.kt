package com.jaco.musicenhance.adapter.apple

import org.junit.Assert.*
import org.junit.Test

class AppleArtworkAddressesTest {
    @Test fun cdnSizeUpgradeAlwaysRetainsOriginalFallback() {
        val original = "https://is1-ssl.mzstatic.com/image/thumb/Music/photo/300x300bb.jpg"
        assertEquals(listOf(original.replace("300x300", "1200x1200"), original), AppleArtworkAddresses.candidates(original))
        assertEquals(listOf(original.replace("300x300", "1400x1400")), AppleArtworkAddresses.candidates(original.replace("300x300", "1400x1400")))
    }
    @Test fun templateUrlsAreResolvedAndNonAppleUrlsAreNeverResized() {
        val template = "https://is1-ssl.mzstatic.com/image/thumb/photo/{w}x{h}bb.{f}"
        assertEquals(listOf("https://is1-ssl.mzstatic.com/image/thumb/photo/1200x1200bb.jpg", "https://is1-ssl.mzstatic.com/image/thumb/photo/600x600bb.jpg"), AppleArtworkAddresses.candidates(template))
        val other = "https://example.org/300x300.jpg"
        assertEquals(listOf(other), AppleArtworkAddresses.candidates(other))
        assertTrue(AppleArtworkAddresses.candidates("content://cover/1").isEmpty())
        assertTrue(AppleArtworkAddresses.candidates("").isEmpty())
    }
    @Test fun nativeCropAndColorPlaceholdersAndZeroSizeAreResolved() {
        val prefix = "https://is1-ssl.mzstatic.com/image/thumb/photo/"
        assertEquals(listOf(prefix + "1200x1200.jpg", prefix + "600x600.jpg"), AppleArtworkAddresses.candidates(prefix + "{w}x{h}{c}{p3}.{f}"))
        assertEquals(listOf(prefix + "1200x1200bb.jpg", prefix + "600x600bb.jpg"), AppleArtworkAddresses.candidates(prefix + "0x0bb.jpg"))
    }
}
