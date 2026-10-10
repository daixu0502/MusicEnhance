package com.jaco.musicenhance.adapter.salt

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.model.RepeatMode
import com.jaco.musicenhance.adapter.discovery.HostInterfaces
import com.jaco.musicenhance.player.media.MediaSessionDataSource

/** Reads native controller flows and invokes explicit playback operations. */
internal class SaltPlaybackSource(loader: ClassLoader) {
    val api = SaltApi.hint(loader)
    private val controls by lazy { SaltControlApi(loader, api) }
    private val controller = loader.loadClass("com.salt.music.service.MusicController")
    private val songType = loader.loadClass("com.salt.music.data.entry.Song")
    private val currentSong get() = controls.currentSong
    private val flowValue get() = currentSong.type.getMethod("getValue")
    private val cover by lazy { HostInterfaces.field(loader, "salt.artwork.flow.v1", known = { controller.getField("\u0860") }) {
        methods { matcher { paramCount = 1; returnType = "java.lang.Object"
            invokeMethods { add { declaredClass = "com.salt.music.service.SongPicture"; name = "getDefaultCoverBitmap\$default" } }
        } }
            .flatMap { it.usingFields }.map { it.field }.filter { it.className == controller.name }
            .map { it.getFieldInstance(loader) }.filter { field -> runCatching { field.type.getMethod("getValue") }.isSuccess }.distinct()
    } }
    private val lyrics by lazy { HostInterfaces.field(loader, "salt.lyrics.flow.v1", known = { controller.getField("\u0791") }) {
        // Generic signatures, when retained, distinguish the document from other StateFlows.
        controller.declaredFields.filter { it.genericType.typeName.contains("androidx.media3.${api.lyricsClass}>") }
    } }
    private val lyricsOwner by lazy { HostInterfaces.field(loader, "salt.lyrics.owner-flow.v1", known = { controller.getField("\u0793") }) {
        // Owner and document are captured together by SaltNativeDataHooks on erased builds.
        emptyList()
    } }
    private val pairType by lazy { HostInterfaces.type(loader, "salt.artwork.pair.v1", known = { loader.loadClass("androidx.media3.${api.coverPairClass}") }) {
        emptyList() // Erased Pair<Object,Object> fields do not identify bitmap and presence safely.
    } }
    private val hasCover by lazy { pairType.getField(api.coverPresentField) }
    private val coverBitmap by lazy { pairType.getField(api.coverBitmapField) }
    private val songId = songType.getMethod("getId")
    private val songTitle = songType.getMethod("getTitle")
    private val songArtist = songType.getMethod("getArtist")
    private val songAlbum = songType.getMethod("getAlbum")
    private val songDuration = songType.getMethod("getDuration")
    private fun isPlaying() = runCatching { controls.playing.invoke(null) == true }.getOrElse { MediaSessionDataSource.snapshot().isPlaying }
    private val main = Handler(Looper.getMainLooper())
    private var released = false
    private var seekGeneration = 0L

    data class Song(val id: String, val title: String, val artist: String, val album: String, val durationMs: Long) {
        fun matches(player: PlayerSnapshot): Boolean {
            // MediaSessionDataSource supplies this label when the local file has no artist tag.
            val artistMatches = artist == player.artist || (artist.isBlank() && player.artist == "未知歌手")
            return title == player.title && artistMatches && album == player.album
        }
    }

    fun song(): Song? {
        val value = flowValue.invoke(currentSong.get(null)) ?: return null
        return Song(songId.invoke(value) as String, songTitle.invoke(value) as String,
            songArtist.invoke(value) as String, songAlbum.invoke(value) as String,
            (songDuration.invoke(value) as Number).toLong())
    }

    /** Reads Salt's unblurred original cache, not a screenshot or notification thumbnail. */
    fun artwork(player: PlayerSnapshot): Bitmap? {
        val requested = song()?.takeIf { it.matches(player) } ?: return null
        val bitmap = runCatching {
            val field = cover
            val pair = field.type.getMethod("getValue").invoke(field.get(null)) ?: return@runCatching null
            if (hasCover.get(pair) != true) null else coverBitmap.get(pair) as? Bitmap
        }.getOrNull() ?: SaltNativeDataHooks.artwork(requested.id) ?: return null
        return bitmap.takeIf { !it.isRecycled && song()?.id == requested.id }
    }

    fun lyricsDocument(song: Song): Any? {
        val owner = lyricsOwner
        val documentFlow = lyrics
        val readOwner = owner.type.getMethod("getValue")
        if (readOwner.invoke(owner.get(null)) != song.id) return null
        val document = documentFlow.type.getMethod("getValue").invoke(documentFlow.get(null)) ?: return null
        return document.takeIf { readOwner.invoke(owner.get(null)) == song.id && this.song()?.id == song.id }
    }

    fun cueOffsetMs(): Long = controls.cueOffset.getLong(null)
    fun repeatMode(): RepeatMode = runCatching {
        val repeat = controls.repeatFlow
        decodeRepeat((repeat.type.getMethod("getValue").invoke(repeat.get(null)) as? Enum<*>)?.name)
    }.getOrDefault(RepeatMode.UNKNOWN)
    fun playPause() = dispatch { if (isPlaying()) controls.pause.invoke(null) else controls.play.invoke(null) }
    fun previous() = dispatch { controls.previous.invoke(null) }
    fun next() = dispatch { controls.next.invoke(null) }
    fun cycleRepeat(): Boolean {
        if (released || repeatMode() == RepeatMode.UNKNOWN) return false
        dispatch { controls.repeat.invoke(null) }
        return true
    }

    fun seekTo(positionMs: Long, player: PlayerSnapshot, resume: Boolean) {
        val requested = song()?.takeIf { it.matches(player) } ?: return
        if (player.durationMs <= 0 || released) return
        val request = ++seekGeneration
        // Salt advances to the next song when seek equals duration exactly.
        val durationMs = if (requested.durationMs > 0) minOf(player.durationMs, requested.durationMs) else player.durationMs
        val targetMs = positionMs.coerceIn(0L, (durationMs - 1).coerceAtLeast(0L))
        dispatch {
            if (request != seekGeneration || song()?.id != requested.id) return@dispatch
            controls.seek.invoke(controls.callback.get(null), targetMs)
            // Explicit play, never toggle: tapping a lyric while playing must not pause it.
            if (resume && !released && request == seekGeneration && song()?.id == requested.id && !isPlaying()) {
                controls.play.invoke(null)
            }
        }
    }

    private fun dispatch(operation: () -> Unit) {
        if (released) return
        main.post {
            if (!released) runCatching(operation).onFailure { moduleInfo("Salt control failed: ${it.cause ?: it}") }
        }
    }

    fun release() {
        released = true
        seekGeneration++
        main.removeCallbacksAndMessages(null)
    }

    companion object {
        fun decodeRepeat(name: String?): RepeatMode = when (name) {
            "CIRCLE" -> RepeatMode.LIST_LOOP
            "REPEAT_ONE" -> RepeatMode.SINGLE_LOOP
            "RANDOM" -> RepeatMode.SHUFFLE
            else -> RepeatMode.UNKNOWN
        }
    }
}
