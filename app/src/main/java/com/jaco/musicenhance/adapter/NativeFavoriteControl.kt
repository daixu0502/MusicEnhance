package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.player.model.PlayerControlState
import com.jaco.musicenhance.player.model.PlayerSnapshot

/** Reads the host's state; dispatching an action never implies that the mutation succeeded. */
internal interface NativeFavoriteControl {
    fun read(player: PlayerSnapshot): PlayerControlState
    fun toggle(player: PlayerSnapshot): Boolean
    fun release() {}
}
