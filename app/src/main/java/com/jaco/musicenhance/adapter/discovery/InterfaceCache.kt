package com.jaco.musicenhance.adapter.discovery

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal class InterfaceCache(private val file: File, private val fingerprint: String) {
    fun read(): Map<String, InterfaceDescriptor> = runCatching {
        if (!file.isFile || file.length() > MAX_CACHE_BYTES) return emptyMap()
        val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        if (json.getString("fingerprint") != fingerprint) return emptyMap()
        val entries = json.getJSONObject("entries")
        require(entries.length() <= MAX_ENTRIES)
        entries.keys().asSequence().associateWith { InterfaceDescriptor.fromJson(entries.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    fun write(entries: Map<String, InterfaceDescriptor>) {
        require(entries.size <= MAX_ENTRIES)
        file.parentFile?.mkdirs()
        // Engines for several host ClassLoaders share a process file. Writes are serialized by
        // the discovery worker, and must retain the other loaders' descriptors from that file.
        val merged = (read() + entries).entries.toList().takeLast(MAX_ENTRIES).associate { it.toPair() }
        val output = JSONObject()
        merged.forEach { (key, value) -> output.put(key, value.toJson()) }
        val bytes = JSONObject().put("fingerprint", fingerprint).put("entries", output)
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_CACHE_BYTES)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }

    companion object {
        private const val MAX_CACHE_BYTES = 256 * 1024
        const val MAX_ENTRIES = 512
        // Change when a semantic matching rule changes, including a signature-compatible change.
        const val RULE_REVISION = 4

        fun fingerprint(packageName: String, versionCode: Long, apkPaths: List<String>): String {
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update("$packageName:$versionCode:$RULE_REVISION".toByteArray())
            apkPaths.sorted().forEach { path ->
                val file = File(path)
                val apkHash = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; apkHash.update(buffer, 0, count) }
                }
                hash.update(file.name.toByteArray()); hash.update(apkHash.digest())
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
