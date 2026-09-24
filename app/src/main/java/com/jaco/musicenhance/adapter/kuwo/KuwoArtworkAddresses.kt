package com.jaco.musicenhance.adapter.kuwo

import java.net.URI

/** size=0 returns the original; the native 1080 request can be capped at 700 by the service. */
internal object KuwoArtworkAddresses {
    fun candidates(
        nativeAddress: String?,
        lookup: (Int) -> String?,
        isCurrent: () -> Boolean,
    ): Sequence<String> = sequence {
        val seen = hashSetOf<String>()
        for (sizePx in REQUEST_SIZES) {
            if (!isCurrent()) return@sequence
            val address = normalize(runCatching { lookup(sizePx) }.getOrNull())
            if (address != null && seen.add(address)) yield(address)
        }
        if (isCurrent()) normalize(nativeAddress)?.takeIf(seen::add)?.let { yield(it) }
    }

    /** The native API returns a plain URL, not JSON. Reject error pages and malformed responses. */
    fun normalize(value: String?): String? = runCatching {
        val text = value?.trim()?.removePrefix("\uFEFF")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = URI(text)
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) return null
        // Legacy kwcdn image hosts do not have matching TLS certificates. The official img1
        // endpoint serves the same /star assets over valid HTTPS; never disable verification.
        if (uri.scheme in listOf("http", "https") && LEGACY_IMAGE_HOST.matches(uri.host.lowercase()) &&
            uri.rawPath.startsWith("/star/") && uri.port == -1
        ) return "https://img1.kuwo.cn${uri.rawPath}" + (uri.rawQuery?.let { "?$it" } ?: "")
        when (uri.scheme?.lowercase()) {
            "https" -> text
            // The legacy native interface can still return HTTP Kuwo CDN addresses.
            "http" -> if (uri.host.equals("kuwo.cn", true) || uri.host.endsWith(".kuwo.cn", true)) {
                "https" + text.substring(text.indexOf(':'))
            } else null
            else -> null
        }
    }.getOrNull()

    private val LEGACY_IMAGE_HOST = Regex("img[1-4]\\.kwcdn\\.kuwo\\.cn")
    private val REQUEST_SIZES = intArrayOf(0, 1080, 360)
}
