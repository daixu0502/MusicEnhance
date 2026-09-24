package com.jaco.musicenhance.adapter.kugou

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.NativePlayerViews
import com.jaco.musicenhance.adapter.PolledFavoriteControl
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Database reads work even while the native player is paused behind our Activity. */
internal class KugouFavoriteSource(private val activity: Activity, private val root: ViewGroup) : PolledFavoriteControl.Source {
    private val loader = activity.classLoader
    private val songs = KugouSongSource(loader)
    private val favoriteType = loader.loadClass("com.kugou.common.utils.MyFavUtils")
    private val favoriteInstance = favoriteType.getMethod("r0")
    private val query = favoriteType.getMethod("d0", Long::class.javaPrimitiveType, String::class.java, String::class.java)
    private val controlType = loader.loadClass("com.kugou.android.app.player.flip.FlipSimpleControlView")
    private val favoriteView = controlType.getMethod("getFavView")

    override fun read(player: PlayerSnapshot): PolledFavoriteControl.State? {
        val song = songs.current()?.takeIf { it.matches(player) } ?: return null
        val index = query.invoke(favoriteInstance.invoke(null), song.mixId, song.hash, song.extraId) as Number
        if (songs.current()?.key != song.key) return null
        return PolledFavoriteControl.State(song.key, index.toLong() >= 0)
    }

    override fun toggle(state: PolledFavoriteControl.State): Boolean {
        if (activity.isFinishing || activity.isDestroyed || songs.current()?.key != state.trackKey) return false
        val controls = NativePlayerViews.find(root, controlType::isInstance) ?: return false
        val view = favoriteView.invoke(controls) as? View ?: return false
        // Synchronize the native action's input with its database, then retain its login/eligibility flow.
        view.javaClass.getMethod("setHasFav", Boolean::class.javaPrimitiveType).invoke(view, state.favorite)
        return view.performClick()
    }
}
