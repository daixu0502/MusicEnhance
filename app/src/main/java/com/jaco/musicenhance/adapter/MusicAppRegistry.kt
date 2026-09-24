package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.adapter.qq.QQPlayerProfile
import com.jaco.musicenhance.adapter.apple.ApplePlayerProfile
import com.jaco.musicenhance.adapter.kugoulite.KugouLitePlayerProfile
import com.jaco.musicenhance.adapter.kuwo.KuwoPlayerProfile

/** Only explicitly registered packages receive hooks; unknown music apps remain untouched. */
internal object MusicAppRegistry {
    val profiles: List<MusicAppProfile> = listOf(QQPlayerProfile, ApplePlayerProfile, KugouLitePlayerProfile, KuwoPlayerProfile)

    fun find(packageName: String?): MusicAppProfile? = profiles.firstOrNull { it.packageName == packageName }

    fun forActivity(className: String?): MusicAppProfile? =
        profiles.firstOrNull { it.ownsActivity(className) }
}

internal fun isHorizontalPlayerActivityName(className: String?): Boolean =
    MusicAppRegistry.forActivity(className)?.isHorizontalPlayerActivity(className) == true
