package com.jaco.musicenhance.adapter.kugoulite

import android.view.ViewGroup
import com.jaco.musicenhance.adapter.NativeFavoriteControl
import com.jaco.musicenhance.adapter.NativePlayerViews
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot

internal class KugouLiteFavoriteControl(private val root: ViewGroup, private val stateTagId: Int) : NativeFavoriteControl {
    // 5.2.9 uses this state tag in both the fragment and land.base.d's SongFavDelegate callback.
    constructor(root: ViewGroup, loader: ClassLoader) : this(root, resolveStateTagId(root, loader))

    private fun button() = if (stateTagId == 0) null else NativePlayerViews.find(root) {
        it.getTag(stateTagId) is Boolean && it.isClickable && it.isEnabled
    }

    override fun read(player: PlayerSnapshot) = PlayerControlState(
        favorite = button()?.getTag(stateTagId) as? Boolean,
        songTitle = player.title,
    )

    override fun toggle(player: PlayerSnapshot): Boolean = button()?.performClick() ?: false

    companion object {
        @Suppress("DiscouragedApi")
        private fun resolveStateTagId(root: ViewGroup, loader: ClassLoader): Int {
            // APK resource names are shortened (id/a, id/b...). The host's R field still holds
            // the correct number; getIdentifier with the uncompressed name returns zero.
            val nativeId = runCatching { loader.loadClass("ml.h").getField("kg_player_song_like_button").getInt(null) }
                .getOrDefault(0)
            val resolved = if (nativeId != 0) nativeId else root.resources.getIdentifier(
                "kg_player_song_like_button", "id", KugouLiteMusicProfile.packageName,
            )
            moduleInfo("Kugou Lite favorite state tag resolved=${resolved != 0}")
            return resolved
        }
    }
}
