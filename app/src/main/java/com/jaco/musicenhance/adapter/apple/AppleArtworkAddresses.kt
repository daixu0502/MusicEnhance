package com.jaco.musicenhance.adapter.apple

import java.net.URI

internal object AppleArtworkAddresses {
    private val size = Regex("(?<=/)(\\d{1,4})x(\\d{1,4})(?=[a-zA-Z.-])")

    fun candidates(address: String): List<String> {
        val original = address.trim()
        val uri = runCatching { URI(original.replace("{", "%7B").replace("}", "%7D")) }.getOrNull() ?: return emptyList()
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return emptyList()
        // Only Apple's image CDN has this sizing contract. Other artwork URLs remain untouched.
        if (!uri.host.endsWith(".mzstatic.com")) return listOf(original)
        val originalSize = size.find(original)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sizes = (listOf(originalSize).filter { it > 0 } + listOf(1200, 600, 300, 150)).distinct().sortedDescending()
        return sizes.map { pixels ->
            size.replace(resolveTemplate(original, pixels)) { "${pixels}x$pixels" }
        }.distinct()
    }

    // These placeholders are also expanded by Apple's common.coil.e request interceptor.
    private fun resolveTemplate(address: String, sizePx: Int) = address
        .replace("{w}", "$sizePx").replace("{h}", "$sizePx")
        .replace("{f}", "jpg").replace("{c}", "").replace("{p3}", "")
}
