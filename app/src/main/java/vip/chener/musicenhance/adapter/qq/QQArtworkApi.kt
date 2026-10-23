package vip.chener.musicenhance.adapter.qq

import vip.chener.musicenhance.player.artwork.PlaylistArtworkWindow
import vip.chener.musicenhance.player.model.RepeatMode
import vip.chener.musicenhance.adapter.qq.QQArtworkSource.Song

/** Discovered native song and artwork URL methods; no network or UI state. */
internal class QQArtworkApi(private val classLoader: ClassLoader) : QQArtworkSource {
    private val songClass = Class.forName("com.tencent.qqmusicplayerprocess.songinfo.SongInfo", false, classLoader)
    private val process = QQPlayProcessApi(classLoader)
    private val getPlayEnvironment = process.getEnvironment
    private val playMethods = process.type
    private val getPlaySong = playMethods.getMethod("getPlaySong")
    private val songTitle = QQInterfaceRules.songTitle(classLoader, songClass)
    private val songId = QQInterfaceRules.songId(classLoader, songClass)
    private val coverUrl = QQInterfaceRules.artwork(classLoader, songClass, "album")
    private val sizedCoverUrl get() = runCatching {
        QQInterfaceRules.artwork(classLoader, songClass, "sized")
    }.getOrNull()
    private val singerCoverUrl get() = runCatching {
        QQInterfaceRules.artwork(classLoader, songClass, "singer")
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
    override fun neighbours(song: Song): List<Song> {
        val (listMethod, focusMethod, modeMethod) = playlistMethods
        val environment = getPlayEnvironment.invoke(null)
        val queue = (listMethod.invoke(environment) as? List<*>)?.toList() ?: return emptyList()
        fun idAt(index: Int): Long? = queue.getOrNull(index)?.let {
            (songId.invoke(it) as? Number)?.toLong()
        }
        val focus = (focusMethod.invoke(environment) as? Number)?.toInt() ?: -1
        val index = if (idAt(focus) == song.id) focus else queue.indices.firstOrNull { idAt(it) == song.id }
            ?: return emptyList()
        val mode = QQRepeatModes.decode((modeMethod.invoke(environment) as? Number)?.toInt() ?: -1)
        val wrap = mode in setOf(RepeatMode.LIST_LOOP, RepeatMode.SINGLE_LOOP, RepeatMode.SHUFFLE)
        if (!isCurrentSong(song)) return emptyList()
        return PlaylistArtworkWindow.indices(queue.size, index, wrap).mapNotNull { neighbourIndex ->
            val nativeSong = queue[neighbourIndex] ?: return@mapNotNull null
            val id = idAt(neighbourIndex)?.takeIf { it > 0 && it != song.id } ?: return@mapNotNull null
            Song(nativeSong, id)
        }.distinctBy(Song::id)
    }

    override fun currentSong(expectedTitle: String): Song? {
        val current = getPlaySong.invoke(getPlayEnvironment.invoke(null)) ?: return null
        if (songTitle.invoke(current) != expectedTitle) return null
        val id = (songId.invoke(current) as? Number)?.toLong() ?: return null
        return Song(current, id)
    }

    override fun isCurrentSong(song: Song): Boolean {
        val current = getPlaySong.invoke(getPlayEnvironment.invoke(null)) ?: return false
        return (songId.invoke(current) as? Number)?.toLong() == song.id
    }

    override fun singerAddress(currentSong: Song, size: QQArtworkAddresses.Size): String? =
        singerCoverUrl?.invoke(null, currentSong.nativeObject, size.builderIndex) as? String

    override fun coverAddress(currentSong: Song, size: QQArtworkAddresses.Size): String? {
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

