package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.adapter.PolledFavoriteControl
import android.app.Activity
import com.jaco.musicenhance.player.model.PlayerSnapshot
import java.lang.reflect.Proxy
import java.util.Locale

/** 5.2.9 collection manager and action delegate; requires only the existing home Activity. */
internal class KugouLiteFavoriteSource(
    private val loader: ClassLoader,
    private val hostActivity: () -> Activity?,
) : PolledFavoriteControl.Source {
    private val api = KugouLiteApi(loader)
    private val songType = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
    private val current = api.currentSong
    private val hash = songType.getMethod("getHashValue")
    private val mixId = songType.getMethod("getMixId")
    private val title = songType.getMethod("getTrackName")
    private val artist = songType.getMethod("getArtistName")
    private val favorite = KugouLiteInterfaceRules.favoriteQuery(loader)
    private val isAiSong = KugouLiteInterfaceRules.aiCheck(loader, timbre = false)
    private val isAiTimbre = KugouLiteInterfaceRules.aiCheck(loader, timbre = true)
    private val providerType = loader.loadClass("com.kugou.android.app.player.subview.base.IProvider")
    private val toggleFavorite = KugouLiteInterfaceRules.favoriteToggle(loader)
    private val delegateType = toggleFavorite.declaringClass
    private val createDelegate = delegateType.getConstructor(providerType)
    private val releaseDelegate = KugouLiteInterfaceRules.favoriteRelease(loader)
    private val providerCurrent = KugouLiteInterfaceRules.providerCurrent(loader, providerType)
    private val providerContribution = KugouLiteInterfaceRules.providerContribution(loader, providerType)

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
    override fun read(player: PlayerSnapshot): PolledFavoriteControl.State? {
        val song = currentSong() ?: return null
        if (song.title.isBlank() || song.title != player.title.trim() ||
            (song.artist.isNotBlank() && player.artist.isNotBlank() && song.artist != player.artist.trim())) return null
        val isFavorite = favorite.invoke(null, song.hash, song.mixId) as Boolean
        if (currentSong()?.key != song.key) return null
        return PolledFavoriteControl.State(song.key, isFavorite)
    }

    /** Called only for an explicit click, on main, as the native delegate may open login/permission UI. */
    override fun toggle(state: PolledFavoriteControl.State): Boolean {
        val activity = hostActivity() ?: return false
        if (activity.isFinishing || activity.isDestroyed || currentSong()?.key != state.trackKey) return false
        val activityType = loader.loadClass("com.kugou.common.base.AbsFrameworkActivity")
        if (!activityType.isInstance(activity)) return false
        // 5.2.9 u()->M()/t() only needs the live home Activity, current track and optional
        // contribution metadata. No native player, LandPlayerPage or ILandProvider is constructed.
        val provider = Proxy.newProxyInstance(loader, arrayOf(providerType)) { proxy, method, args ->
            when {
                method.name == "getActivity" -> hostActivity()?.takeIf(activityType::isInstance)
                    ?: error("Kugou Lite home Activity unavailable")
                method.name == "getContext" -> activity.applicationContext
                method == providerCurrent -> current.invoke(null)
                method == providerContribution -> null // Nullable outside the native contribution UI.
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args?.firstOrNull()
                method.name == "toString" -> "MusicEnhanceFavoriteProvider"
                else -> error("Unsupported Kugou Lite favorite provider call: ${method.name}")
            }
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

}
