package com.jaco.musicenhance.adapter.salt

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerSnapshot
import com.jaco.musicenhance.player.model.RepeatMode

/** Private names below are verified against Salt Player 12.3.2 (2026090601). */
internal class SaltPlaybackSource(loader: ClassLoader) {
    private val controller = loader.loadClass("com.salt.music.service.MusicController")
    private val songType = loader.loadClass("com.salt.music.data.entry.Song")
    private val flowValue = loader.loadClass("androidx.media3.l14").getMethod("getValue")
    private val currentSong = controller.getField("\u07a0")
    private val cover = controller.getField("\u0860")
    private val lyrics = controller.getField("\u0791")
    private val lyricsOwner = controller.getField("\u0793")
    private val repeat = controller.getField("\u07a4")
    private val pairType = loader.loadClass("androidx.media3.eh2")
    private val hasCover = pairType.getField("\u052e")
    private val coverBitmap = pairType.getField("\u052f")
    private val songId = songType.getMethod("getId")
    private val songTitle = songType.getMethod("getTitle")
    private val songArtist = songType.getMethod("getArtist")
    private val songAlbum = songType.getMethod("getAlbum")
    private val songDuration = songType.getMethod("getDuration")
    private val isPlaying = controller.getMethod("\u078c")
    private val play = controller.getMethod("\u0794")
    private val pause = controller.getMethod("\u0792")
    private val next = controller.getMethod("\u0798")
    private val previous = controller.getMethod("\u079a")
    private val changeRepeat = controller.getMethod("\u0780")
    private val playback = controller.getField("\u078b")
    private val seek = playback.type.getMethod("\u052e", Long::class.javaPrimitiveType)
    private val cueOffset = loader.loadClass("androidx.media3.jd3").getField("\u0868")
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
        val pair = flowValue.invoke(cover.get(null)) ?: return null
        if (hasCover.get(pair) != true) return null // Salt's default artwork is not an album cover.
        val bitmap = coverBitmap.get(pair) as? Bitmap ?: return null
        return bitmap.takeIf { !it.isRecycled && song()?.id == requested.id }
    }

    fun lyricsDocument(song: Song): Any? {
        if (flowValue.invoke(lyricsOwner.get(null)) != song.id) return null
        val document = flowValue.invoke(lyrics.get(null)) ?: return null
        return document.takeIf { flowValue.invoke(lyricsOwner.get(null)) == song.id && this.song()?.id == song.id }
    }

    fun cueOffsetMs(): Long = cueOffset.getLong(null)
    fun repeatMode(): RepeatMode = decodeRepeat((flowValue.invoke(repeat.get(null)) as? Enum<*>)?.name)
    fun playPause() = dispatch { if (isPlaying.invoke(null) == true) pause.invoke(null) else play.invoke(null) }
    fun previous() = dispatch { previous.invoke(null) }
    fun next() = dispatch { next.invoke(null) }
    fun cycleRepeat(): Boolean {
        if (released || repeatMode() == RepeatMode.UNKNOWN) return false
        dispatch { changeRepeat.invoke(null) }
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
            seek.invoke(playback.get(null), targetMs)
            // Explicit play, never toggle: tapping a lyric while playing must not pause it.
            if (resume && !released && request == seekGeneration && song()?.id == requested.id && isPlaying.invoke(null) != true) {
                play.invoke(null)
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
