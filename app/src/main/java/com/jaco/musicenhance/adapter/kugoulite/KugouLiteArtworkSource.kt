package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.adapter.kugoucommon.KugouArtworkAddresses
import com.jaco.musicenhance.player.artwork.PlaylistArtworkSource
import com.jaco.musicenhance.player.artwork.PlaylistArtworkWindow
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.util.Locale

/** 5.2.9's current-song URL, read on the artwork worker without retaining mutable host objects. */
internal class KugouLiteArtworkSource(loader: ClassLoader) : PlaylistArtworkSource<KugouLiteArtworkSource.Song> {
    data class Song(val hash: String, val mixId: Long, val title: String, val artist: String, val address: String?) {
        val key: String get() = "kugoulite:$mixId:${hash.lowercase(Locale.ROOT)}"
        fun matches(player: PlayerSnapshot): Boolean = title.isNotBlank() && title == player.title.trim() &&
            (artist.isBlank() || player.artist.isBlank() || artist == player.artist.trim())
    }

    private val service = loader.loadClass("com.kugou.framework.service.util.PlaybackServiceUtil")
    private val songType = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
    // s0 is current, I1 is NEXT; using I1 makes artwork/lyrics fail metadata validation.
    private val current = service.getMethod("s0")
    private val hash = songType.getMethod("getHashValue")
    private val mixId = songType.getMethod("getMixId")
    private val title = songType.getMethod("getTrackName")
    private val artist = songType.getMethod("getArtistName")
    private val image = songType.getMethod("b1") // KGMusic.getImgUrl(), or the local KGFile image URL.
    private val queueSize by lazy { service.getMethod("V1") }
    private val queuePosition by lazy { service.getMethod("N1") }
    private val queueMode by lazy { service.getMethod("M1") }
    private val queueRange by lazy {
        service.getMethod("J1", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }

    private fun read(native: Any? = current.invoke(null)): Song? {
        native ?: return null
        val hashValue = (hash.invoke(native) as? String).orEmpty().trim()
        val mixValue = (mixId.invoke(native) as? Number)?.toLong() ?: 0L
        if (hashValue.isBlank() && mixValue <= 0) return null
        return Song(hashValue, mixValue, (title.invoke(native) as? String).orEmpty().trim(),
            (artist.invoke(native) as? String).orEmpty().trim(), image.invoke(native) as? String)
    }

    override fun currentSong(player: PlayerSnapshot): Song? = read()?.takeIf { it.matches(player) }
    override fun isCurrentSong(song: Song): Boolean = read()?.key == song.key
    override fun cacheKey(song: Song): String = song.key
    override fun neighbours(song: Song): List<Song> {
        if (!isCurrentSong(song)) return emptyList()
        val size = queueSize.invoke(null) as Int
        val position = queuePosition.invoke(null) as Int
        val mode = queueMode.invoke(null) as Int
        if (position !in 0 until size) return emptyList()
        // N1 -> Ec -> v4 -> PlayQueue.m is the current queue index. J1(start, count)
        // reads only the selected range and lets the host fill in missing song metadata.
        fun at(index: Int): Song? = read((queueRange.invoke(null, index, 1) as? Array<*>)?.firstOrNull())
        if (at(position)?.key != song.key) return emptyList()
        // Shuffle's future permutation is private to the service: cache playlist neighbours,
        // without claiming these are the next randomly selected songs.
        val result = PlaylistArtworkWindow.indices(size, position, wrap = mode == 1)
            .mapNotNull(::at).filter { it.key != song.key }.distinctBy { it.key }
        return result.takeIf {
            isCurrentSong(song) && queuePosition.invoke(null) == position &&
                queueSize.invoke(null) == size && queueMode.invoke(null) == mode && at(position)?.key == song.key
        }.orEmpty()
    }
    override fun addresses(song: Song, isCurrent: () -> Boolean): Sequence<String> =
        KugouArtworkAddresses.candidates(song.address, isCurrent)
}
