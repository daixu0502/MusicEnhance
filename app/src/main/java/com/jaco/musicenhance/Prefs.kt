package com.jaco.musicenhance

import android.content.SharedPreferences

object Prefs {
    const val NAME = "musicenhance"
    const val HOOK_QQ_MUSIC = "hook_qq_music"
    const val HOOK_APPLE_MUSIC = "hook_apple_music"
    const val HOOK_KUGOU_LITE_MUSIC = "hook_kugou_lite_music"
    const val HOOK_KUWO_MUSIC = "hook_kuwo_music"
    const val KEEP_COVER_SCREEN_ON = "keep_cover_screen_on"

    // The first concept-edition build used this key. Never reuse it for standard Kugou.
    private const val LEGACY_HOOK_KUGOU_LITE_MUSIC = "hook_kugou_music"

    fun isHookEnabled(preferences: SharedPreferences?, key: String): Boolean {
        preferences ?: return false
        val storedKey = if (key == HOOK_KUGOU_LITE_MUSIC && !preferences.contains(key)) {
            LEGACY_HOOK_KUGOU_LITE_MUSIC
        } else key
        return preferences.getBoolean(storedKey, false)
    }
}
