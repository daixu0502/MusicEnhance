package com.jaco.musicenhance.adapter.qq

import com.jaco.musicenhance.player.model.RepeatMode

internal object QQRepeatModes {
    private const val SINGLE_LOOP = 101
    private const val LIST_LOOP = 103
    private const val LEGACY_SHUFFLE = 104
    private const val SHUFFLE = 105
    private const val SEQUENTIAL = 106

    // Verified against QQ's PlayDefine.PlayMode. 102 is obsolete; sequential playback is 106.
    fun decode(mode: Int): RepeatMode = when (mode) {
        LIST_LOOP -> RepeatMode.LIST_LOOP
        SINGLE_LOOP -> RepeatMode.SINGLE_LOOP
        LEGACY_SHUFFLE, SHUFFLE -> RepeatMode.SHUFFLE
        SEQUENTIAL -> RepeatMode.SEQUENTIAL
        else -> RepeatMode.UNKNOWN
    }

    fun next(mode: Int): Int = when (decode(mode)) {
        RepeatMode.SEQUENTIAL -> LIST_LOOP
        RepeatMode.LIST_LOOP -> SINGLE_LOOP
        RepeatMode.SINGLE_LOOP -> SHUFFLE
        RepeatMode.SHUFFLE -> SEQUENTIAL
        RepeatMode.UNKNOWN -> LIST_LOOP
    }
}
