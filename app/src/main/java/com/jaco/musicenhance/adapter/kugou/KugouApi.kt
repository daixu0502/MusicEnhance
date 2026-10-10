package com.jaco.musicenhance.adapter.kugou

/** Contracts checked against 20.8.2 and 20.8.4; select by signature, never by a guessed version. */
internal class KugouApi(private val loader: ClassLoader) {
    val utility: Class<*> = loader.loadClass("com.kugou.framework.service.util.PlaybackServiceUtil")
    private val wrapper = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
    val updated = runCatching { utility.getMethod("Q1").returnType != wrapper }.getOrDefault(true)
    val currentSong get() = KugouInterfaceRules.currentSong(loader) {
        utility.getMethod(if (updated) "R1" else "Q1").also { check(it.returnType == wrapper) }
    }
    val service by lazy {
        KugouInterfaceRules.service(loader) { utility.getMethod(if (updated) "F2" else "E2").also {
            check(it.returnType == loader.loadClass("com.kugou.framework.service.IKugouPlaybackService"))
        } }
    }
    fun method(old: String, updated: String, vararg parameters: Class<*>) = when (old) {
        "Ce" -> KugouInterfaceRules.repeatSetter(loader) { utility.getMethod(if (this.updated) updated else old, *parameters) }
        "Q4", "v4", "t4", "l4" -> KugouInterfaceRules.queueMethod(loader, when (old) {
            "Q4" -> "size"; "v4" -> "position"; "t4" -> "mode"; else -> "range"
        }) { utility.getMethod(if (this.updated) updated else old, *parameters) }
        else -> error("No semantic rule for Kugou utility $old")
    }
}
