package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.adapter.qq.QQMusicProfile

/** Only explicitly registered packages receive hooks; unknown music apps remain untouched. */
internal object MusicAppRegistry {
    val profiles: List<MusicAppProfile> = listOf(QQMusicProfile)

    fun find(packageName: String?): MusicAppProfile? = profiles.firstOrNull { it.packageName == packageName }

    fun forActivity(className: String?): MusicAppProfile? =
        profiles.firstOrNull { className?.startsWith("${it.packageName}.") == true }
}

internal fun isPlayerActivityName(className: String?): Boolean =
    MusicAppRegistry.forActivity(className)?.isPlayerActivity(className) == true

internal fun isHorizontalPlayerActivityName(className: String?): Boolean =
    MusicAppRegistry.forActivity(className)?.isHorizontalPlayerActivity(className) == true
