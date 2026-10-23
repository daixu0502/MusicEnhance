package vip.chener.musicenhance.adapter.lxx

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import vip.chener.musicenhance.hook.module
import vip.chener.musicenhance.hook.moduleInfo
import vip.chener.musicenhance.hook.safeHook
import vip.chener.musicenhance.player.PlayerDataSource
import vip.chener.musicenhance.player.media.MediaSessionDataSource
import vip.chener.musicenhance.player.model.PlayerControlState
import vip.chener.musicenhance.player.model.PlayerSnapshot
import vip.chener.musicenhance.adapter.discovery.HostInterfaces
import java.lang.reflect.Modifier
import java.lang.reflect.Executable
import java.lang.ref.WeakReference
import io.github.libxposed.api.XposedInterface

/** TrackPlayer belongs to the main looper. Reads here are in-process getters, never native IPC. */
internal class LxxPlaybackSource : PlayerDataSource by MediaSessionDataSource {
    private var released = false
    private var failureReported = false

    override fun snapshot(): PlayerSnapshot = read(PlayerSnapshot.Empty) { api, player ->
        val song = LxxStateBridge.state
        val track = api.track.invoke(player)
        val bundle = track?.let { api.trackData.get(it) as? Bundle }
        val title = song.title.ifBlank { bundle?.getString("title").orEmpty() }
        val artist = if (song.id.isBlank()) bundle?.getString("artist").orEmpty() else song.artist
        val album = if (song.id.isBlank()) bundle?.getString("album").orEmpty() else song.album
        if (title.isBlank()) return@read PlayerSnapshot.Empty
        val matched = song.id.isBlank() || song.id == bundle?.getString("musicId")
        val durationMs = if (matched) (api.duration.invoke(player) as Number).toLong().coerceAtLeast(0) else 0L
        PlayerSnapshot(
            title, artist, album, artwork = null, durationMs = durationMs,
            positionMs = if (matched) (api.position.invoke(player) as Number).toLong().coerceIn(0, durationMs) else 0L,
            isPlaying = matched && (api.state.invoke(player) as Number).toInt() in PLAYING_STATES,
            controls = PlayerControlState(songTitle = title),
        )
    }

    override fun controlState() = LxxControlSource.snapshot(snapshot().title)

    fun currentSongId(): String? = read(null) { api, player ->
        api.track.invoke(player)?.let { (api.trackData.get(it) as? Bundle)?.getString("musicId") }
    }

    fun artwork(): Bitmap? = read(null) { api, player ->
        val song = LxxStateBridge.state
        if (song.id.isBlank() || song.picture.isBlank() || song.id != currentSongId()) return@read null
        val metadata = api.metadata.invoke(api.manager.get(player)) ?: return@read null
        val uri = api.artworkUri.get(metadata) as? Uri
        // Metadata may arrive before/after TrackPlayer's queue. Both ID and loaded URI must agree.
        if (uri?.toString() != song.picture) return@read null
        (api.artwork.get(metadata) as? Bitmap)?.takeUnless { it.isRecycled }
    }

    fun playPause() = read(Unit) { api, player ->
        if ((api.state.invoke(player) as Number).toInt() in PLAYING_STATES) api.pause.invoke(player) else api.play.invoke(player)
        Unit
    }

    fun seek(positionMs: Long, resume: Boolean) = read(Unit) { api, player ->
        val song = LxxStateBridge.state
        if (song.id.isNotBlank() && song.id != currentSongId()) return@read Unit
        api.seek.invoke(player, positionMs)
        if (resume) api.play.invoke(player)
        Unit
    }

    fun release() { released = true }

    private inline fun <T> read(fallback: T, block: (Api, Any) -> T): T {
        if (released || Looper.myLooper() != Looper.getMainLooper()) return fallback
        val api = installedApi ?: return fallback
        val player = activePlayer.get() ?: return fallback
        return runCatching { block(api, player) }.onFailure {
            if (!failureReported) { failureReported = true; moduleInfo("LX-X playback read failed: ${it.cause ?: it}") }
        }.getOrDefault(fallback)
    }

    private class Api(loader: ClassLoader) {
        val type = loader.loadClass("com.guichaguri.trackplayer.service.player.ExoPlayback")
        val track = type.getMethod("getCurrentTrack")
        val trackData = track.returnType.declaredFields.single { it.type == Bundle::class.java }.apply { isAccessible = true }
        val position = type.getMethod("getPosition")
        val duration = type.getMethod("getDuration")
        val state = type.getMethod("getState")
        val play = type.getMethod("play")
        val pause = type.getMethod("pause")
        val seek = type.getMethod("seekTo", Long::class.javaPrimitiveType)
        val manager = HostInterfaces.field(loader, "lxx.playback.manager.v1", known = { type.getDeclaredField("manager") },
            validate = { !Modifier.isStatic(it.modifiers) }) {
            type.declaredFields.filter { field -> !Modifier.isStatic(field.modifiers) && field.type.declaredMethods.any(::isMetadata) }
        }.apply { isAccessible = true }
        val metadata = HostInterfaces.method(loader, "lxx.playback.metadata.v1", known = { manager.type.getMethod("d") },
            validate = ::isMetadata) {
            // This is TrackPlayer's metadata holder, whose unblurred art has a typed source URI.
            manager.type.declaredMethods.filter(::isMetadata)
        }.apply { isAccessible = true }
        val artwork = metadata.returnType.declaredFields.single { it.type == Bitmap::class.java }.apply { isAccessible = true }
        val artworkUri = metadata.returnType.declaredFields.single { it.type == Uri::class.java }.apply { isAccessible = true }

        private fun isMetadata(method: java.lang.reflect.Method) = method.parameterCount == 0 &&
            method.returnType.declaredFields.count { it.type == Bitmap::class.java } == 1 &&
            method.returnType.declaredFields.count { it.type == Uri::class.java } == 1
    }

    companion object {
        private val PLAYING_STATES = setOf(3, 6) // STATE_PLAYING, STATE_BUFFERING in this TrackPlayer build.
        private var installedApi: Api? = null
        private var activePlayer = WeakReference<Any>(null)
        private val installed = mutableSetOf<Executable>()

        fun install(loader: ClassLoader) = safeHook("LX-X TrackPlayer 26.09.20") {
            val api = Api(loader)
            hook(api.type.getMethod("initialize"), "musicenhance.lxx.player.ready") { chain ->
                val result = chain.proceed()
                activePlayer = WeakReference(chain.thisObject)
                result
            }
            hook(api.type.getMethod("destroy"), "musicenhance.lxx.player.destroy") { chain ->
                if (activePlayer.get() === chain.thisObject) activePlayer.clear()
                chain.proceed()
            }
            installedApi = api
        }

        @Synchronized private fun hook(executable: Executable, id: String, hooker: XposedInterface.Hooker) {
            if (executable in installed) return
            module.installHook(executable, id, hooker)
            installed.add(executable)
        }
    }
}
