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
        val template = resolveTemplate(original, 1200)
        val high = size.replace(template) { match ->
            if (match.groupValues[1].toInt() < 1200) "1200x1200" else match.value
        }
        val fallback = resolveTemplate(original, 600).replace("/0x0bb", "/600x600bb")
        return listOf(high, fallback).distinct()
    }

    // These placeholders are also expanded by Apple's common.coil.e request interceptor.
    private fun resolveTemplate(address: String, sizePx: Int) = address
        .replace("{w}", "$sizePx").replace("{h}", "$sizePx")
        .replace("{f}", "jpg").replace("{c}", "").replace("{p3}", "")
}
