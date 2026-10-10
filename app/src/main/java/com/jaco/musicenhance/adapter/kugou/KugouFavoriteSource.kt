package com.jaco.musicenhance.adapter.kugou

import android.app.Activity
import com.jaco.musicenhance.adapter.PolledFavoriteControl
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Native collection business operation, with home-page context for login/payment prompts. */
internal class KugouFavoriteSource(loader: ClassLoader, private val hostActivity: () -> Activity?) : PolledFavoriteControl.Source {
    private val songs = KugouSongSource(loader)
    private val favoriteType = loader.loadClass("com.kugou.common.utils.MyFavUtils")
    private val favoriteInstance = favoriteType.getMethod("r0")
    private val query = favoriteType.getMethod("d0", Long::class.javaPrimitiveType, String::class.java, String::class.java)
    private val wrapperType = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
    private val musicType = loader.loadClass("com.kugou.android.common.entity.KGMusic")
    private val nativeMusic = wrapperType.getMethod("q2")
    private val feesType = loader.loadClass("com.kugou.common.musicfees.MusicFeesInterface")
    private val toggle = favoriteType.methods.single {
        it.name == "D" && it.parameterCount == 5 && it.parameterTypes[1] == musicType &&
            it.parameterTypes[2] == String::class.java && it.parameterTypes[3] == feesType &&
            it.parameterTypes[4] == Int::class.javaPrimitiveType
    }
    private val faceType = toggle.parameterTypes[0]
    private val face = faceType.getMethod("g", String::class.java, Int::class.javaPrimitiveType, String::class.java, String::class.java)

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
        val origin = face.invoke(null, key.javaClass.getMethod("b").invoke(key),
            key.javaClass.getMethod("a").invoke(key), key.javaClass.getMethod("c").invoke(key), "")
        val fees = activity.javaClass.getMethod("getMusicFeesDelegate").invoke(activity)
        // D is the same native collection path used by FlipSimpleControlView; it retains
        // account/song restrictions and persistence without creating or clicking that View.
        musicType.getMethod("setTargetFavState", Boolean::class.javaPrimitiveType).invoke(music, !state.favorite)
        toggle.invoke(favoriteInstance.invoke(null), origin, music, activity.javaClass.name, fees, 0)
        return true
    }
}
