package com.jaco.musicenhance.adapter.qq

/** QQ native objects stay inside the adapter. All methods may perform IPC; use worker threads. */
internal interface QQArtworkSource {
    data class Song(val nativeObject: Any, val id: Long)

    fun currentSong(expectedTitle: String): Song?
    fun isCurrentSong(expected: Song): Boolean
    fun neighbours(expected: Song): List<Song>
    fun singerAddress(currentSong: Song, size: QQArtworkFallback.Size): String?
    fun coverAddress(currentSong: Song, size: QQArtworkFallback.Size): String?
}
