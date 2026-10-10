package com.jaco.musicenhance.adapter.kugoulite

import com.jaco.musicenhance.adapter.NativeRepeatControl
import com.jaco.musicenhance.player.model.RepeatMode

/** 5.2.9 IPC API. Read n0 directly; L1 masks unavailable services as REPEAT_ALL. */
internal class KugouLiteRepeatSource(loader: ClassLoader) : NativeRepeatControl.Api {
    private val api = KugouLiteApi(loader)
    private val service = api.service
    private val readMode = api.readMode
    private val setMode = api.setMode

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
