package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.adapter.NativeRepeatControl
import com.jaco.musicenhance.player.model.RepeatMode

/** 5.2.9 IPC API. Read n0 directly; L1 masks unavailable services as REPEAT_ALL. */
internal class KugouLiteRepeatApi(loader: ClassLoader) : NativeRepeatControl.Api {
    private val utility = loader.loadClass("com.kugou.framework.service.util.PlaybackServiceUtil")
    private val service = utility.getMethod("P0")
    private val readMode = loader.loadClass("com.kugou.framework.service.IKugouPlaybackService").getMethod("n0")
    private val setMode = utility.getMethod("S6", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)

    override fun read() = decode(readMode.invoke(service.invoke(null)) as Int)

    override fun advance() {
        val current = readMode.invoke(service.invoke(null)) as Int
        val next = when (current) { 1 -> 2; 2 -> 3; 3 -> 1; else -> return }
        setMode.invoke(null, next, false)
    }

    companion object {
        fun decode(value: Int) = when (value) {
            1 -> RepeatMode.LIST_LOOP
            2 -> RepeatMode.SINGLE_LOOP
            3 -> RepeatMode.SHUFFLE
            else -> RepeatMode.UNKNOWN
        }
    }
}
