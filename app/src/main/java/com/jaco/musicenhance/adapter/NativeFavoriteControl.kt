package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Reads the host's state; a successful click never implies that a server mutation succeeded. */
internal interface NativeFavoriteControl {
    fun read(player: PlayerSnapshot): PlayerControlState
    fun toggle(player: PlayerSnapshot): Boolean
}
