package vip.chener.musicenhance.adapter.kugou

import vip.chener.musicenhance.player.model.PlayerSnapshot
import java.util.Locale

/** Immutable identities copied from the native service; never retain its mutable queue objects. */
internal class KugouSongSource(loader: ClassLoader) {
    data class Song(
        val hash: String, val mixId: Long, val extraId: String,
        val title: String, val artist: String,
    ) {
        val key get() = "kugou:$mixId:${hash.lowercase(Locale.ROOT)}:$extraId"
        fun matches(player: PlayerSnapshot) = title.isNotBlank() && title == player.title.trim() &&
            (artist.isBlank() || player.artist.isBlank() || artist == player.artist.trim())
    }

    private val current = KugouApi(loader).currentSong
    private val type = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
    private val hash = type.getMethod("getHashValue")
    private val mixId = type.getMethod("getMixId")
    private val extraId = type.getMethod("getExtraId")
    private val title = type.getMethod("getTrackName")
    private val artist = type.getMethod("getArtistName")

    fun currentNative(): Any? = current.invoke(null)
    fun current(): Song? = read(currentNative())
    fun read(native: Any?): Song? {
        native ?: return null
        val hashValue = (hash.invoke(native) as? String).orEmpty().trim()
        val id = (mixId.invoke(native) as Number).toLong()
        if (hashValue.isBlank() && id <= 0) return null
        return Song(hashValue, id, (extraId.invoke(native) as? String).orEmpty(),
            (title.invoke(native) as? String).orEmpty().trim(), (artist.invoke(native) as? String).orEmpty().trim())
    }
}
