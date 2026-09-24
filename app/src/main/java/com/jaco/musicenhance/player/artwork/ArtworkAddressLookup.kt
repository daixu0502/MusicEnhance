package com.jaco.musicenhance.player.artwork

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Small text responses from artwork lookup APIs; never accepts unbounded HTML/JSON downloads. */
internal object ArtworkAddressLookup {
    private const val MAX_RESPONSE_BYTES = 16 * 1024

    fun read(address: String, isCurrent: () -> Boolean): String? {
        var url = URL(address)
        repeat(3) {
            if (!isCurrent() || url.protocol != "https") return null
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 2_000
                connection.readTimeout = 2_000
                connection.instanceFollowRedirects = false
                val status = connection.responseCode
                if (!isCurrent()) return null
                if (status in 300..399) {
                    url = URL(url, connection.getHeaderField("Location") ?: return null)
                } else {
                    if (status != HttpURLConnection.HTTP_OK || connection.contentLengthLong > MAX_RESPONSE_BYTES) return null
                    return connection.inputStream.use { input ->
                        val bytes = ByteArrayOutputStream()
                        val buffer = ByteArray(1024)
                        while (true) {
                            if (!isCurrent()) return null
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (bytes.size() + count > MAX_RESPONSE_BYTES) return null
                            bytes.write(buffer, 0, count)
                        }
                        bytes.toString(Charsets.UTF_8.name()).takeIf { isCurrent() }
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }
}
