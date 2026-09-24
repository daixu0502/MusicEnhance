package com.jaco.musicenhance.adapter.kugoulite

import android.app.Activity
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.lang.reflect.Modifier
import java.util.Locale

/** 5.2.9's favorite database and native action delegate, independent of player button tags. */
internal class KugouLiteFavoriteSource(private val activity: Activity) : KugouLiteFavoriteControl.Source {
    private val loader = activity.classLoader
    private val service = loader.loadClass("com.kugou.framework.service.util.PlaybackServiceUtil")
    private val songType = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
    private val current = service.getMethod("s0")
    private val hash = songType.getMethod("getHashValue")
    private val mixId = songType.getMethod("getMixId")
    private val title = songType.getMethod("getTrackName")
    private val artist = songType.getMethod("getArtistName")
    private val favorite = loader.loadClass("com.kugou.common.utils.MyFavUtils")
        .getMethod("O", String::class.java, Long::class.javaPrimitiveType)
    private val songInterface = loader.loadClass("com.kugou.android.common.entity.ISong")
    private val songKinds = loader.loadClass("ra0.b")
    private val isAiSong = songKinds.getMethod("j", songInterface)
    private val isAiTimbre = songKinds.getMethod("o", songInterface)
    private val providerType = loader.loadClass("com.kugou.android.app.player.subview.base.IProvider")
    private val delegateType = loader.loadClass("com.kugou.android.app.player.song.delegate.SongFavDelegate")
    private val createDelegate = delegateType.getConstructor(providerType)
    private val toggleFavorite = delegateType.getMethod("u", Boolean::class.javaPrimitiveType)
    private val releaseDelegate = delegateType.getMethod("v")

    private data class Song(val key: String, val hash: String, val mixId: Long, val title: String, val artist: String)

    private fun currentSong(): Song? {
        val native = current.invoke(null) ?: return null
        // AI songs use a separate online library; ordinary favorites must not report a false state for them.
        if (isAiSong.invoke(null, native) == true || isAiTimbre.invoke(null, native) == true) return null
        val songHash = (hash.invoke(native) as? String).orEmpty().trim()
        val songMixId = (mixId.invoke(native) as Number).toLong()
        if (songHash.isBlank() && songMixId <= 0) return null
        return Song("$songMixId:${songHash.lowercase(Locale.ROOT)}", songHash, songMixId,
            (title.invoke(native) as? String).orEmpty().trim(), (artist.invoke(native) as? String).orEmpty().trim())
    }

    /** Called on the worker: O(hash, mixId) reads the same cache/database used by SongFavDelegate. */
    override fun read(player: PlayerSnapshot): KugouLiteFavoriteControl.State? {
        val song = currentSong() ?: return null
        if (song.title.isBlank() || song.title != player.title.trim() ||
            (song.artist.isNotBlank() && player.artist.isNotBlank() && song.artist != player.artist.trim())) return null
        val isFavorite = favorite.invoke(null, song.hash, song.mixId) as Boolean
        if (currentSong()?.key != song.key) return null
        return KugouLiteFavoriteControl.State(song.key, isFavorite)
    }

    /** Called only for an explicit click, on main, as the native delegate may open login/permission UI. */
    override fun toggle(state: KugouLiteFavoriteControl.State): Boolean {
        if (activity.isFinishing || activity.isDestroyed || currentSong()?.key != state.trackKey) return false
        // LandPlayerActivity.b -> p.a -> LandPlayerPage.e. Resolve by declared type because
        // Kotlin source names differ from the actual obfuscated field names in the APK.
        val api = fieldOfType(activity, "com.kugou.android.app.player.land.p")
        val page = api?.let { fieldOfType(it, "com.kugou.android.app.player.land.ILandPlayerPage") }
        val provider = page?.let { fieldOfType(it, "com.kugou.android.app.player.land.ILandProvider") }
        if (provider == null) {
            moduleInfo("Kugou Lite favorite: native provider unavailable; activity=${activity.javaClass.name}")
            return false
        }
        val delegate = createDelegate.newInstance(provider)
        try {
            // Keeps host login, song eligibility, statistics and favorite persistence behavior.
            // No synthetic View click: paused/light-mode pages may have no initialized like button.
            toggleFavorite.invoke(delegate, state.favorite)
        } finally {
            releaseDelegate.invoke(delegate)
        }
        return true
    }

    private fun fieldOfType(instance: Any, typeName: String): Any? {
        val expected = loader.loadClass(typeName)
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            type.declaredFields.firstOrNull { !Modifier.isStatic(it.modifiers) && expected.isAssignableFrom(it.type) }
                ?.let { return it.apply { isAccessible = true }.get(instance) }
            type = type.superclass
        }
        return null
    }
}
