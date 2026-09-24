package com.jaco.musicenhance.player.model


internal enum class RepeatMode { UNKNOWN, LIST_LOOP, SINGLE_LOOP, SHUFFLE, SEQUENTIAL }

internal data class PlayerControlState(
    val repeatMode: RepeatMode = RepeatMode.UNKNOWN,
    val favorite: Boolean? = null,
    val songTitle: String? = null,
    val favoritePending: Boolean = false,
)
