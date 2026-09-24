package com.jaco.musicenhance.adapter.kuwo

import com.jaco.musicenhance.adapter.NativeRepeatControl
import com.jaco.musicenhance.player.model.RepeatMode

/** 12.2.2.4: mode values verified against MIUIFlipPlayPageFragment's icon resources. */
internal class KuwoRepeatApi(loader: ClassLoader) : NativeRepeatControl.Api {
    private val player = loader.loadClass("q1.b").getMethod("e0")
    private val type = loader.loadClass("cn.kuwo.mod.playcontrol.c")
    private val readMode = type.getMethod("getPlayMode")
    private val writeMode = type.getMethod("setPlayMode", Int::class.javaPrimitiveType)

    override fun read() = decode(readMode.invoke(player.invoke(null)) as Int)

    override fun advance() {
        val target = player.invoke(null)
        val next = when (readMode.invoke(target) as Int) { 2 -> 0; 0 -> 3; 3 -> 1; 1 -> 2; else -> return }
        writeMode.invoke(target, next)
    }

    companion object {
        fun decode(value: Int) = when (value) {
            0 -> RepeatMode.SINGLE_LOOP
            1 -> RepeatMode.SEQUENTIAL
            2 -> RepeatMode.LIST_LOOP
            3 -> RepeatMode.SHUFFLE
            else -> RepeatMode.UNKNOWN
        }
    }
}
