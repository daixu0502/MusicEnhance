package com.jaco.musicenhance.adapter.qq

import android.graphics.Bitmap
import java.net.URI
import com.jaco.musicenhance.player.artwork.ArtworkDimensions

/** QQ 20.8.5.8 PhotoDomainUrlBuilder: pin the quality column for smaller sizes. */
internal object QQArtworkFallback {
    data class Size(val pixels: Int, val builderIndex: Int, val qualityColumn: Int? = null)

    private val sizes = listOf(
        Size(1500, 4), Size(1200, 3), Size(800, 5),
        Size(500, 2, 1), Size(300, 1, 1), Size(150, 0, 0),
    )

    // Confirmed shared QQ cover for tracks with album.id=0, including live recordings.
    // Match its identity, not visual simplicity: plain/monochrome real album art is valid.
    private val placeholderPath = Regex("(?:^|/)T002R\\d+x\\d+M0000030lak94GN5Ad_0\\.[^/]+$")
    fun isPlaceholderAddress(address: String): Boolean = runCatching {
        placeholderPath.containsMatchIn(URI(address).path.orEmpty())
    }.getOrDefault(false)

    fun loadAlbumOrSinger(
        albumAddress: (Size) -> String?,
        singerAddress: (Size) -> String?,
        download: (String) -> Bitmap?,
        isCurrent: () -> Boolean,
        onAttempt: (String, Size, Bitmap?, Throwable?) -> Unit = { _, _, _, _ -> },
    ): Bitmap? = load(albumAddress, download, isCurrent) { size, bitmap, error ->
        onAttempt("album", size, bitmap, error)
    } ?: load(singerAddress, download, isCurrent) { size, bitmap, error ->
        onAttempt("singer", size, bitmap, error)
    }

    fun load(
        address: (Size) -> String?,
        download: (String) -> Bitmap?,
        isCurrent: () -> Boolean,
        onAttempt: (Size, Bitmap?, Throwable?) -> Unit = { _, _, _ -> },
    ): Bitmap? {
        val attemptedUrls = mutableSetOf<String>()
        for (size in sizes) {
            if (!isCurrent()) return null
            val attempt = runCatching {
                val url = address(size)?.takeIf { it.isNotBlank() } ?: return@runCatching null
                if (isPlaceholderAddress(url)) return@runCatching null
                if (!attemptedUrls.add(url)) return@runCatching null
                download(url)?.takeUnless { it.isRecycled }
                    ?.takeIf { ArtworkDimensions.isUsable(it.width, it.height) }
            }
            if (!isCurrent()) return null
            val bitmap = attempt.getOrNull()
            onAttempt(size, bitmap, attempt.exceptionOrNull())
            if (bitmap != null) {
                // A server may cap its original at 1000/1080 pixels. Smaller requests cannot
                // improve that valid response; publishing it now avoids extra network waits.
                return bitmap
            }
        }
        return null
    }
}
