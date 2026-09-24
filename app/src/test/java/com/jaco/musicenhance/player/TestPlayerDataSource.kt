package com.jaco.musicenhance.player

import com.jaco.musicenhance.player.model.PlayerSnapshot

internal class TestPlayerDataSource(var song: PlayerSnapshot = PlayerSnapshot.Empty) : PlayerDataSource {
    val listeners = linkedSetOf<(PlayerSnapshot) -> Unit>()
    var snapshotReads = 0
    override fun snapshot(): PlayerSnapshot { snapshotReads++; return song }
    override fun addListener(listener: (PlayerSnapshot) -> Unit) { listeners += listener; listener(song) }
    override fun removeListener(listener: (PlayerSnapshot) -> Unit) { listeners -= listener }
    override fun bassLevel() = 0.4f
    fun publish(snapshot: PlayerSnapshot) {
        song = snapshot
        listeners.toList().forEach { it(snapshot) }
    }
}
