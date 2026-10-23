package vip.chener.musicenhance.adapter

import vip.chener.musicenhance.player.model.PlayerControlState
import vip.chener.musicenhance.player.model.PlayerSnapshot

/** Reads the host's state; dispatching an action never implies that the mutation succeeded. */
internal interface NativeFavoriteControl {
    fun read(player: PlayerSnapshot): PlayerControlState
    fun toggle(player: PlayerSnapshot): Boolean
    fun release() {}
}
