package com.jaco.musicenhance.adapter.kugou

/** Contracts checked against 20.8.2 and 20.8.4; select by signature, never by a guessed version. */
internal class KugouApi(loader: ClassLoader) {
    val utility: Class<*> = loader.loadClass("com.kugou.framework.service.util.PlaybackServiceUtil")
    private val wrapper = loader.loadClass("com.kugou.framework.service.entity.KGMusicWrapper")
    val updated = runCatching { utility.getMethod("Q1").returnType != wrapper }.getOrDefault(true)
    val currentSong = utility.getMethod(if (updated) "R1" else "Q1").also { check(it.returnType == wrapper) }
    val service by lazy {
        utility.getMethod(if (updated) "F2" else "E2").also {
            check(it.returnType == loader.loadClass("com.kugou.framework.service.IKugouPlaybackService"))
        }
    }
    fun method(old: String, updated: String, vararg parameters: Class<*>) =
        utility.getMethod(if (this.updated) updated else old, *parameters)
}
