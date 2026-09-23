package com.jaco.musicenhance.player.artwork

import java.io.File
import java.security.MessageDigest

/** Encoded artwork, not decoded bitmaps. Access only on background workers. */
internal class ArtworkDiskCache(
    private val directory: File,
    private val maxBytes: Long = 64L * 1024 * 1024,
) {
    // A foreground read must never observe a partial prefetch write.
    @Synchronized
    fun read(address: String): ByteArray? = runCatching {
        val file = fileFor(address)
        if (!file.isFile || file.length() !in 1..MAX_IMAGE_BYTES) return null
        file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
    }.getOrNull()

    @Synchronized
    fun contains(address: String): Boolean = runCatching {
        fileFor(address).let { file ->
            (file.isFile && file.length() in 1..MAX_IMAGE_BYTES).also { exists ->
                if (exists) file.setLastModified(System.currentTimeMillis())
            }
        }
    }.getOrDefault(false)

    @Synchronized
    fun write(address: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES || bytes.size > maxBytes) return
        runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) return
            val destination = fileFor(address)
            val temporary = File.createTempFile("artwork-", ".tmp", directory)
            try {
                temporary.writeBytes(bytes)
                if (temporary.renameTo(destination)) trim()
            } finally {
                temporary.delete()
            }
        }
    }

    @Synchronized
    fun remove(address: String) { runCatching { fileFor(address).delete() } }

    private fun fileFor(address: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(address.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(directory, "$hash.image")
    }

    private fun trim() {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".image") } ?: return
        var bytes = files.sumOf(File::length)
        for (file in files.sortedBy(File::lastModified)) {
            if (bytes <= maxBytes) break
            val size = file.length()
            if (file.delete()) bytes -= size
        }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024L
    }
}
