package vip.chener.musicenhance.adapter.kugou

import vip.chener.musicenhance.adapter.kugoucommon.KugouArtworkAddresses
import vip.chener.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures
import vip.chener.musicenhance.adapter.discovery.HostInterfaces
import org.luckypray.dexkit.query.enums.StringMatchType
import vip.chener.musicenhance.player.artwork.PlaylistArtworkSource
import vip.chener.musicenhance.player.artwork.PlaylistArtworkWindow
import vip.chener.musicenhance.player.model.PlayerSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Queue reads, address resolution and decoding are all invoked by the artwork workers. */
internal class KugouArtworkSource(loader: ClassLoader) : PlaylistArtworkSource<KugouArtworkSource.Song> {
    data class Song(val identity: KugouSongSource.Song, val avatarHash: String, val addresses: List<String>)
    private val songs = KugouSongSource(loader)
    private val api = KugouApi(loader)
    private val type = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
    private val image = KugouInterfaceRules.image(loader) { type.getMethod("f2") }
    private val avatar by lazy { KugouInterfaceFeatures.verified(loader, "kugou", "artwork.avatar-address", type, "X1", String::class.java) }
    private val avatarHash by lazy { KugouInterfaceFeatures.verified(loader, "kugou", "artwork.avatar-hash", type, "d2", String::class.java) }
    private val queueSize by lazy { api.method("Q4", "R4") }
    private val queuePosition by lazy { api.method("v4", "w4") }
    private val queueMode by lazy { api.method("t4", "u4") }
    private val queueRange by lazy { api.method("l4", "m4", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!) }
    private val lookup by lazy { AvatarLookup(loader) }

    private fun read(native: Any?): Song? {
        val identity = songs.read(native) ?: return null
        return Song(identity, runCatching { avatarHash.invoke(native) as? String }.getOrNull().orEmpty().ifBlank { identity.hash },
            listOfNotNull(runCatching { avatar.invoke(native) as? String }.getOrNull(), image.invoke(native) as? String)
                .filter { it.isNotBlank() && it != "-" }.distinct())
    }
    override fun currentSong(player: PlayerSnapshot) = read(songs.currentNative())?.takeIf { it.identity.matches(player) }
    override fun isCurrentSong(song: Song) = songs.current()?.key == song.identity.key
    override fun cacheKey(song: Song) = song.identity.key

    override fun neighbours(song: Song): List<Song> {
        if (!isCurrentSong(song)) return emptyList()
        val size = queueSize.invoke(null) as Int
        val position = queuePosition.invoke(null) as Int
        val mode = queueMode.invoke(null) as Int
        if (position !in 0 until size) return emptyList()
        fun at(index: Int) = read((queueRange.invoke(null, index, 1) as? Array<*>)?.firstOrNull())
        if (at(position)?.identity?.key != song.identity.key) return emptyList()
        val result = PlaylistArtworkWindow.indices(size, position, wrap = mode == 1)
            .mapNotNull(::at).filter { it.identity.key != song.identity.key }.distinctBy { it.identity.key }
        return result.takeIf {
            isCurrentSong(song) && queuePosition.invoke(null) == position && queueSize.invoke(null) == size &&
                queueMode.invoke(null) == mode && at(position)?.identity?.key == song.identity.key
        }.orEmpty()
    }

    override fun addresses(song: Song, isCurrent: () -> Boolean): Sequence<String> = sequence {
        for (address in song.addresses) yieldAll(KugouArtworkAddresses.candidates(address, isCurrent))
        if (!isCurrent()) return@sequence
        // Run only after known addresses failed, so a missing URL lookup cannot delay a valid image.
        val resolved = lookup.resolve(song, isCurrent)
        yieldAll(KugouArtworkAddresses.candidates(resolved, isCurrent))
    }.distinct()

    private class AvatarLookup(loader: ClassLoader) {
        private val type = HostInterfaces.type(loader, "kugou.artwork.avatar-manager.v1",
            { loader.loadClass("com.kugou.android.mymusic.songAvatar.SongAvatarManager") },
            { owner -> owner.declaredMethods.any { it.returnType == owner && it.parameterCount == 0 } },
        ) {
            methods { searchPackages("com.kugou.android.mymusic.songAvatar"); matcher {
                paramTypes("java.util.List", "java.lang.Runnable")
                addUsingString("SongAvatarManager doFinalRequest ", StringMatchType.Contains)
            } }.map { it.getClassInstance(loader) }.distinct()
        }
        private val instance = KugouInterfaceFeatures.verified(loader, "kugou", "artwork.avatar-instance", type, "j", type)
        private val cached = KugouInterfaceFeatures.verified(loader, "kugou", "artwork.avatar-cached", type, "l", String::class.java,
            Long::class.javaPrimitiveType!!, String::class.java)
        private val request = HostInterfaces.method(loader, "kugou.artwork.avatar-request.v1",
            { type.getDeclaredMethod("h", List::class.java, Runnable::class.java) },
            { it.parameterTypes.contentEquals(arrayOf(List::class.java, Runnable::class.java)) && it.returnType == Void.TYPE },
            discover = { emptyList() },
        ).apply { isAccessible = true }
        private val entryType = HostInterfaces.type(loader, "kugou.artwork.avatar-entry.v1",
            { loader.loadClass(if (KugouApi(loader).updated) "ig5.b" else "ag5.b") },
            { owner -> owner.constructors.any { it.parameterCount == 0 } }, discover = { emptyList() },
        )

        fun resolve(song: Song, active: () -> Boolean): String? {
            val manager = instance.invoke(null) ?: return null
            fun readCache() = (cached.invoke(manager, song.identity.mixId, song.avatarHash) as? String)?.takeIf { it.isNotBlank() }
            readCache()?.let { return it }
            if (!active()) return null
            val entry = entryType.getConstructor().newInstance()
            fun text(method: String, value: String) { entryType.getMethod(method, String::class.java).invoke(entry, value) }
            text("g", song.avatarHash)
            text("f", "${song.identity.artist} - ${song.identity.title}")
            text("j", "")
            entryType.getMethod("h", Long::class.javaPrimitiveType).invoke(entry, song.identity.mixId)
            val completed = CountDownLatch(1)
            request.invoke(manager, listOf(entry), Runnable { completed.countDown() })
            // Host networking owns its callback. Bound the wait and never publish into the current song here.
            repeat(LOOKUP_WAIT_STEPS) {
                if (!active() || Thread.currentThread().isInterrupted) return null
                if (completed.await(LOOKUP_STEP_MS, TimeUnit.MILLISECONDS)) return readCache()
            }
            return readCache()
        }
    }
    private companion object {
        const val LOOKUP_WAIT_STEPS = 20
        const val LOOKUP_STEP_MS = 100L
    }
}
