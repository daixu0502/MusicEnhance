package com.jaco.musicenhance.adapter.kugoulite

import java.net.URI
import java.util.Locale

/** Kugou image templates use {size}; only recognised image CDN paths permit size replacement. */
internal object KugouLiteArtworkAddresses {
    // CDN size=0 returns the actual source; fixed large sizes can upscale a small original.
    private val sizesPx = listOf(0, 1080, 720, 480)
    private val knownSizesPx = setOf(0, 100, 120, 150, 160, 200, 240, 300, 320, 360, 400, 480, 500, 600, 640, 720, 750, 800, 960, 1000, 1024, 1080, 1500, 1920, 2048)
    private val sizedPath = Regex("^/(stdmusic|albumcover|softhead)/(\\d+|\\{size\\})/")

    fun candidates(raw: String?, isCurrent: () -> Boolean): Sequence<String> = sequence {
        val address = raw?.trim()?.takeIf { it.isNotEmpty() && it != "-" } ?: return@sequence
        val normalized = if (address.startsWith("//")) "https:$address" else address
        // Replace braces only for URI validation; retain the template for candidate generation.
        val uri = runCatching { URI(normalized.replace("{size}", "480")) }.getOrNull() ?: return@sequence
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@sequence
        val kugouCdn = host == "kugou.com" || host.endsWith(".kugou.com")
        val https = when {
            uri.scheme == "https" -> normalized
            uri.scheme == "http" && kugouCdn -> "https:" + normalized.substringAfter(':')
            else -> return@sequence
        }
        val seen = hashSetOf<String>()
        val pathStart = https.indexOf('/', "https://".length)
        val prefix = if (pathStart < 0) https else https.substring(0, pathStart)
        val suffix = if (pathStart < 0) "" else https.substring(pathStart)
        val sized = sizedPath.find(suffix)?.takeIf { kugouCdn && it.groupValues[2].toIntOrNull() in knownSizesPx }
        val variants = if (kugouCdn && https.contains("{size}")) {
            sizesPx.map { https.replace("{size}", it.toString()) }
        } else if (sized != null && uri.rawQuery == null) {
            // Signed URLs are used unchanged; rewriting them would invalidate their signature.
            sizesPx.map { size -> prefix + suffix.replaceRange(sized.range, "/${sized.groupValues[1]}/$size/") }
        } else emptyList()
        val originalSize = sized?.groupValues?.get(2)?.toIntOrNull()
        val preferOriginal = originalSize == 0
        val candidates = if (preferOriginal) listOf(https) + variants else variants + listOf(https)
        for (candidate in candidates) {
            if (!isCurrent()) return@sequence
            if ('{' !in candidate && seen.add(candidate)) yield(candidate)
        }
    }
}
