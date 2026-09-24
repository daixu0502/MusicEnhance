package com.jaco.musicenhance.adapter.kugou

import com.jaco.musicenhance.adapter.NativeRepeatControl
import com.jaco.musicenhance.player.model.RepeatMode

internal class KugouRepeatSource(loader: ClassLoader) : NativeRepeatControl.Api {
    private val utility = loader.loadClass("com.kugou.framework.service.util.PlaybackServiceUtil")
    private val service = utility.getMethod("E2")
    private val mode = loader.loadClass("com.kugou.framework.service.IKugouPlaybackService").getMethod("getPlayMode")
    private val setMode = utility.getMethod("Ce", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
    override fun read() = decode(mode.invoke(service.invoke(null)) as Int)
    override fun advance() {
        val next = when (mode.invoke(service.invoke(null)) as Int) { 1 -> 2; 2 -> 3; 3 -> 1; else -> return }
        // Native setter retains mode restrictions, without opening the host's selection dialog.
        setMode.invoke(null, next, false)
    }
    companion object {
        fun decode(mode: Int) = when (mode) {
            1 -> RepeatMode.LIST_LOOP
            2 -> RepeatMode.SINGLE_LOOP
            3 -> RepeatMode.SHUFFLE
            else -> RepeatMode.UNKNOWN
        }
    }
}
