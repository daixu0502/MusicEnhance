package com.jaco.musicenhance.adapter.kuwo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KuwoArtworkAddressesTest {
    @Test fun legacyCdnUsesVerifiedHttpsEndpointWithoutChangingImagePath() {
        for (scheme in listOf("http", "https")) {
            val path = "/star/albumcover/700/s4s16/77/163479630.jpg"
            assertEquals("https://img1.kuwo.cn$path", KuwoArtworkAddresses.normalize("$scheme://img1.kwcdn.kuwo.cn$path"))
            assertEquals("https://img1.kuwo.cn$path?version=2", KuwoArtworkAddresses.normalize("$scheme://img4.kwcdn.kuwo.cn$path?version=2"))
        }
        assertNull(KuwoArtworkAddresses.normalize("http://img1.kwcdn.kuwo.cn.evil.test/star/cover.jpg"))
        assertEquals("https://other.example/star/a.jpg", KuwoArtworkAddresses.normalize("https://other.example/star/a.jpg"))
    }

    @Test fun originalAndHighResolutionFailureFallBackToLegacyThenNative() {
        val sizes = mutableListOf<Int>()
        val addresses = KuwoArtworkAddresses.candidates("http://img1.kuwo.cn/album.jpg", { size ->
            sizes += size
            if (size != 360) error("lookup timeout") else "https://img1.kuwo.cn/small.jpg"
        }, { true }).toList()
        assertEquals(listOf(0, 1080, 360), sizes)
        assertEquals(listOf("https://img1.kuwo.cn/small.jpg", "https://img1.kuwo.cn/album.jpg"), addresses)
    }

    @Test fun successfulOriginalDoesNotRequestSmallerImagesUntilNeeded() {
        val sizes = mutableListOf<Int>()
        val address = KuwoArtworkAddresses.candidates(null, { size ->
            sizes += size
            "https://img1.kuwo.cn/$size.jpg"
        }, { true }).first()
        assertEquals("https://img1.kuwo.cn/0.jpg", address)
        assertEquals(listOf(0), sizes)
    }

    @Test fun invalidAndDuplicateResponsesDoNotReplaceNativeFallback() {
        assertEquals(listOf("https://img1.kuwo.cn/cover.jpg"), KuwoArtworkAddresses.candidates(
            "https://img1.kuwo.cn/cover.jpg", { "https://img1.kuwo.cn/cover.jpg" }, { true },
        ).toList())
        for (value in listOf(null, "", "NO_PIC", "<html>not found</html>", "{\"url\":\"https://img1.kuwo.cn/a.jpg\"}",
            "file:///cover.jpg", "https://user:password@img1.kuwo.cn/a", "http://kuwo.cn.evil.test/a")) {
            assertNull(value, KuwoArtworkAddresses.normalize(value))
        }
    }

    @Test fun cancellationStopsLookupsAndNativeFallback() {
        var active = true
        val sizes = mutableListOf<Int>()
        val iterator = KuwoArtworkAddresses.candidates("https://img1.kuwo.cn/native.jpg", { size ->
            sizes += size
            "https://img1.kuwo.cn/$size.jpg"
        }, { active }).iterator()
        assertEquals("https://img1.kuwo.cn/0.jpg", iterator.next())
        active = false
        assertEquals(false, iterator.hasNext())
        assertEquals(listOf(0), sizes)
    }

    @Test fun originalDownloadFailureCanAdvanceToHighResolutionWithoutRepeatingTheUrl() {
        val requested = mutableListOf<Int>()
        val addresses = KuwoArtworkAddresses.candidates(null, { size ->
            requested += size
            "https://img1.kuwo.cn/$size.jpg"
        }, { true }).iterator()
        assertEquals("https://img1.kuwo.cn/0.jpg", addresses.next())
        // The consumer could not decode/download the original and asks for the next candidate.
        assertEquals("https://img1.kuwo.cn/1080.jpg", addresses.next())
        assertEquals(listOf(0, 1080), requested)
    }
}
