package com.jaco.musicenhance.adapter.kugou

import android.app.Activity
import com.jaco.musicenhance.adapter.PolledFavoriteControl
import com.jaco.musicenhance.adapter.kugoucommon.KugouInterfaceFeatures
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Native collection business operation, with home-page context for login/payment prompts. */
internal class KugouFavoriteSource(private val loader: ClassLoader, private val hostActivity: () -> Activity?) : PolledFavoriteControl.Source {
    private val songs = KugouSongSource(loader)
    private val favoriteType = loader.loadClass("com.kugou.common.utils.MyFavUtils")
    private val favoriteInstance = KugouInterfaceRules.favoriteInstance(loader) { favoriteType.getMethod("r0") }
    private val query = KugouInterfaceRules.favoriteQuery(loader) {
        favoriteType.getMethod("d0", Long::class.javaPrimitiveType, String::class.java, String::class.java)
    }
    private val wrapperType = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
    private val musicType = loader.loadClass("com.kugou.android.common.entity.KGMusic")
    private val nativeMusic = KugouInterfaceRules.nativeMusic(loader) { wrapperType.getMethod("q2") }
    private val feesType = loader.loadClass("com.kugou.common.musicfees.MusicFeesInterface")
    private val toggle = KugouInterfaceRules.favoriteToggle(loader) { favoriteType.methods.single {
        it.name == "D" && it.parameterCount == 5 && it.parameterTypes[1] == musicType &&
            it.parameterTypes[2] == String::class.java && it.parameterTypes[3] == feesType &&
            it.parameterTypes[4] == Int::class.javaPrimitiveType
    } }
    private val faceType = toggle.parameterTypes[0]
    private val face = KugouInterfaceFeatures.verified(loader, "kugou", "favorite.face-factory", faceType, "g", faceType,
        String::class.java, Int::class.javaPrimitiveType!!, String::class.java, String::class.java)

    override fun read(player: PlayerSnapshot): PolledFavoriteControl.State? {
        val song = songs.current()?.takeIf { it.matches(player) } ?: return null
        val index = query.invoke(favoriteInstance.invoke(null), song.mixId, song.hash, song.extraId) as Number
        if (songs.current()?.key != song.key) return null
        return PolledFavoriteControl.State(song.key, index.toLong() >= 0)
    }

    override fun toggle(state: PolledFavoriteControl.State): Boolean {
        val activity = hostActivity() ?: return false
        if (activity.isFinishing || activity.isDestroyed || songs.current()?.key != state.trackKey) return false
        val wrapper = songs.currentNative() ?: return false
        if (songs.read(wrapper)?.key != state.trackKey) return false
        val music = nativeMusic.invoke(wrapper) ?: return false
        val key = activity.javaClass.getMethod("getFaceKey").invoke(activity) ?: return false
        val page = KugouInterfaceFeatures.verified(loader, "kugou", "favorite.face-page", key.javaClass, "b", String::class.java)
        val source = KugouInterfaceFeatures.verified(loader, "kugou", "favorite.face-source", key.javaClass, "a", Int::class.javaPrimitiveType!!)
        val parent = KugouInterfaceFeatures.verified(loader, "kugou", "favorite.face-parent", key.javaClass, "c", String::class.java)
        val origin = face.invoke(null, page.invoke(key), source.invoke(key), parent.invoke(key), "")
        val fees = activity.javaClass.getMethod("getMusicFeesDelegate").invoke(activity)
        // D is the same native collection path used by FlipSimpleControlView; it retains
        // account/song restrictions and persistence without creating or clicking that View.
        musicType.getMethod("setTargetFavState", Boolean::class.javaPrimitiveType).invoke(music, !state.favorite)
        toggle.invoke(favoriteInstance.invoke(null), origin, music, activity.javaClass.name, fees, 0)
        return true
    }
}
