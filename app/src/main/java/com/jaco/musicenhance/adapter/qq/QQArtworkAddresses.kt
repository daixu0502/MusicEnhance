package com.jaco.musicenhance.adapter.qq

import java.net.URI

/** QQ 20.8.5.8 PhotoDomainUrlBuilder sizes. Album candidates precede singer fallback. */
internal object QQArtworkAddresses {
    data class Size(val pixels: Int, val builderIndex: Int, val qualityColumn: Int? = null)
    private val sizes = listOf(
        Size(1500, 4), Size(1200, 3), Size(800, 5),
        Size(500, 2, 1), Size(300, 1, 1), Size(150, 0, 0),
    )
    private val placeholderPath = Regex("(?:^|/)T002R\\d+x\\d+M0000030lak94GN5Ad_0\\.[^/]+$")

    fun isPlaceholderAddress(address: String): Boolean = runCatching {
        placeholderPath.containsMatchIn(URI(address).path.orEmpty())
    }.getOrDefault(false)

    fun candidates(album: (Size) -> String?, singer: (Size) -> String?, active: () -> Boolean): Sequence<String> = sequence {
        val seen = hashSetOf<String>()
        for (lookup in listOf(album, singer)) for (size in sizes) {
            if (!active()) return@sequence
            val address = runCatching { lookup(size) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            if (!isPlaceholderAddress(address) && seen.add(address)) yield(address)
        }
    }
}
