package com.jaco.musicenhance.adapter.qq

import com.jaco.musicenhance.player.artwork.PlaylistArtworkWindow
import com.jaco.musicenhance.player.model.RepeatMode
import com.jaco.musicenhance.adapter.qq.QQArtworkSource.Song

/** QQ 20.8.5.8 private song and artwork URL methods; no network or UI state. */
internal class QQArtworkApi(classLoader: ClassLoader) : QQArtworkSource {
    private val songClass = Class.forName("com.tencent.qqmusicplayerprocess.songinfo.SongInfo", false, classLoader)
    private val getPlayEnvironment = Class.forName("com.tencent.qqmusic.common.ipc.MusicProcess", false, classLoader).getMethod("playEnv")
    private val playMethods = Class.forName("com.tencent.qqmusic.common.ipc.IPlayProcessMethods", false, classLoader)
    private val getPlaySong = playMethods.getMethod("getPlaySong")
    private val songTitle = songClass.getMethod("j3")
    private val songId = songClass.getMethod("C3")
    private val urlBuilder = Class.forName("com.tencent.qqmusiccommon.appconfig.albumpic.b", false, classLoader)
    private val coverUrl = urlBuilder.getMethod("e", songClass, Int::class.javaPrimitiveType)
    private val sizedCoverUrl = runCatching {
        urlBuilder.getMethod("f", songClass, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    }.getOrNull()
    private val singerCoverUrl = runCatching {
        urlBuilder.getMethod("o", songClass, Int::class.javaPrimitiveType)
    }.getOrNull()

    // Optional: a missing queue API must not disable current-song artwork on another QQ version.
    private val playlistMethods by lazy {
        Triple(
            playMethods.getMethod("getPlayListInActualPlay"),
            playMethods.getMethod("getPlayFocusInActualPlay"),
            playMethods.getMethod("getPlayMode"),
        )
    }

    /** QQ returns shuffle order here as well; never guess neighbours from the visible library. */
    override fun neighbours(expected: Song): List<Song> {
        val (listMethod, focusMethod, modeMethod) = playlistMethods
        val environment = getPlayEnvironment.invoke(null)
        val queue = (listMethod.invoke(environment) as? List<*>)?.toList() ?: return emptyList()
        fun idAt(index: Int): Long? = queue.getOrNull(index)?.let {
            (songId.invoke(it) as? Number)?.toLong()
        }
        val focus = (focusMethod.invoke(environment) as? Number)?.toInt() ?: -1
        val index = if (idAt(focus) == expected.id) focus else queue.indices.firstOrNull { idAt(it) == expected.id }
            ?: return emptyList()
        val mode = QQRepeatModes.decode((modeMethod.invoke(environment) as? Number)?.toInt() ?: -1)
        val wrap = mode in setOf(RepeatMode.LIST_LOOP, RepeatMode.SINGLE_LOOP, RepeatMode.SHUFFLE)
        if (!isCurrentSong(expected)) return emptyList()
        return PlaylistArtworkWindow.indices(queue.size, index, wrap).mapNotNull { neighbourIndex ->
            val nativeSong = queue[neighbourIndex] ?: return@mapNotNull null
            val id = idAt(neighbourIndex)?.takeIf { it > 0 && it != expected.id } ?: return@mapNotNull null
            Song(nativeSong, id)
        }.distinctBy(Song::id)
    }

    override fun currentSong(expectedTitle: String): Song? {
        val current = getPlaySong.invoke(getPlayEnvironment.invoke(null)) ?: return null
        if (songTitle.invoke(current) != expectedTitle) return null
        val id = (songId.invoke(current) as? Number)?.toLong() ?: return null
        return Song(current, id)
    }

    override fun isCurrentSong(expected: Song): Boolean {
        val current = getPlaySong.invoke(getPlayEnvironment.invoke(null)) ?: return false
        return (songId.invoke(current) as? Number)?.toLong() == expected.id
    }

    override fun singerAddress(currentSong: Song, size: QQArtworkFallback.Size): String? =
        singerCoverUrl?.invoke(null, currentSong.nativeObject, size.builderIndex) as? String

    override fun coverAddress(currentSong: Song, size: QQArtworkFallback.Size): String? {
        val explicitSizeMethod = sizedCoverUrl
        if (size.qualityColumn != null && explicitSizeMethod != null) {
            val explicit = runCatching {
                explicitSizeMethod.invoke(null, currentSong.nativeObject, size.builderIndex, size.qualityColumn) as? String
            }.getOrNull()
            if (!explicit.isNullOrBlank()) return explicit
        }
        return coverUrl.invoke(null, currentSong.nativeObject, size.builderIndex) as? String
    }
}

