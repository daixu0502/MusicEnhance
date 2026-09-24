package com.jaco.musicenhance.adapter.kuwo

import android.os.SystemClock
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.artwork.ArtworkAddressLookup
import com.jaco.musicenhance.player.artwork.PlaylistArtworkSource
import com.jaco.musicenhance.player.artwork.PlaylistArtworkWindow
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Verified against Kuwo 12.2.2.4; snapshots avoid retaining mutable native Music objects. */
internal class KuwoArtworkSource(loader: ClassLoader) : PlaylistArtworkSource<KuwoArtworkSource.Song> {
    data class Song(val id: Long, val title: String, val artist: String, val album: String, val address: String?)

    private val modules = loader.loadClass("q1.b")
    private val controlType = loader.loadClass("cn.kuwo.mod.playcontrol.c")
    private val musicType = loader.loadClass("cn.kuwo.base.bean.Music")
    private val listType = loader.loadClass("cn.kuwo.base.bean.MusicList")
    private val urlsType = loader.loadClass("cn.kuwo.base.utils.s2")
    private val queueType by lazy { loader.loadClass("cn.kuwo.mod.playcontrol.n") }
    private val controlMethod = modules.getMethod("e0")
    private val currentMethod = controlType.getMethod("X")
    private val queueMethod = controlType.getMethod("a7")
    private val modeMethod = controlType.getMethod("getPlayMode")
    private val songsMethod = listType.getMethod("getPlayList")
    private val idField = musicType.getField("rid")
    private val titleField = musicType.getField("name")
    private val artistField = musicType.getField("artist")
    private val albumField = musicType.getField("album")
    private val pictureMethod = musicType.getMethod("getItemPicUrl")
    private val lookupMethod = urlsType.getMethod(
        "p9", Long::class.javaPrimitiveType, String::class.java, String::class.java,
        String::class.java, Int::class.javaPrimitiveType,
    )

    override fun currentSong(player: PlayerSnapshot): Song? {
        val song = readSong(currentMethod.invoke(controlMethod.invoke(null))) ?: return null
        if (song.title != player.title.trim()) return null
        if (player.artist.isNotBlank() && song.artist.isNotBlank() && song.artist != player.artist.trim()) return null
        if (player.album.isNotBlank() && song.album.isNotBlank() && song.album != player.album.trim()) return null
        return song
    }

    override fun isCurrentSong(song: Song): Boolean {
        val native = currentMethod.invoke(controlMethod.invoke(null)) ?: return false
        return idField.getLong(native) == song.id
    }

    override fun cacheKey(song: Song) = "kuwo:${song.id}"

    override fun neighbours(song: Song): List<Song> {
        val control = controlMethod.invoke(null) ?: return emptyList()
        if (!isCurrentSong(song)) return emptyList()
        // a7 is the active list; p1 points to the previous list and must not be used here.
        val queue = queueMethod.invoke(control) ?: return emptyList()
        val nativeSongs = songsMethod.invoke(queue) as? List<*> ?: return emptyList()
        val currentIndex = nativeSongs.indexOfFirst { it != null && idField.getLong(it) == song.id }
        if (currentIndex < 0) return emptyList()
        val mode = (modeMethod.invoke(control) as? Number)?.toInt()
        val shuffled = if (mode == 3) shuffleOrder(nativeSongs.size, currentIndex) else null
        val indices = if (shuffled != null) {
            // At shuffle boundaries Kuwo can generate a new permutation; do not predict that order.
            PlaylistArtworkWindow.indices(shuffled.size, shuffled.indexOf(currentIndex), wrap = false).map { shuffled[it] }
        } else PlaylistArtworkWindow.indices(nativeSongs.size, currentIndex, wrap = mode == 2)
        val result = indices.mapNotNull { readSong(nativeSongs[it]) }.distinctBy { it.id }.filter { it.id != song.id }
        return result.takeIf { isCurrentSong(song) }.orEmpty()
    }

    private fun shuffleOrder(size: Int, currentIndex: Int): IntArray? = runCatching {
        val queue = queueType.getMethod("O").invoke(null) ?: return null
        val order = (queueType.getDeclaredField("m").apply { isAccessible = true }.get(queue) as? IntArray)?.clone()
            ?: return null
        val position = queueType.getDeclaredField("o").apply { isAccessible = true }.getInt(queue)
        order.takeIf {
            it.size == size && it.toSet().size == size && it.all { index -> index in 0 until size } &&
                position in it.indices && it[position] == currentIndex
        }
    }.getOrNull()

    override fun addresses(song: Song, isCurrent: () -> Boolean): Sequence<String> =
        KuwoArtworkAddresses.candidates(song.address, lookup = { sizePx ->
            val startedAtMs = SystemClock.elapsedRealtime()
            val result = runCatching {
                if (!isCurrent()) return@runCatching null
                val address = KuwoArtworkAddresses.normalize(
                    lookupMethod.invoke(null, song.id, song.title, song.artist, song.album, sizePx) as? String,
                )
                address?.let { ArtworkAddressLookup.read(it, isCurrent) }
            }
            if (isCurrent()) {
                val outcome = result.exceptionOrNull()?.let { (it.cause ?: it).javaClass.simpleName }
                    ?: if (KuwoArtworkAddresses.normalize(result.getOrNull()) == null) "no-address" else "resolved"
                moduleInfo("Kuwo artwork lookup: song=${song.id}, requested=$sizePx, elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}, result=$outcome")
            }
            result.getOrNull()
        }, isCurrent = isCurrent)

    private fun readSong(native: Any?): Song? {
        if (native == null) return null
        val id = idField.getLong(native)
        // Local/unidentified tracks keep their native/media artwork; never share a cache key of zero.
        if (id <= 0) return null
        return Song(
            id, (titleField.get(native) as? String).orEmpty().trim(),
            (artistField.get(native) as? String).orEmpty().trim(),
            (albumField.get(native) as? String).orEmpty().trim(),
            pictureMethod.invoke(native) as? String,
        )
    }
}
