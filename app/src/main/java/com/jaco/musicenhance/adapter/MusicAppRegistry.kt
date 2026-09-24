package com.jaco.musicenhance.adapter

import com.jaco.musicenhance.adapter.qq.QQPlayerProfile
import com.jaco.musicenhance.adapter.apple.ApplePlayerProfile
import com.jaco.musicenhance.adapter.kugoulite.KugouLitePlayerProfile
import com.jaco.musicenhance.adapter.kugou.KugouPlayerProfile
import com.jaco.musicenhance.adapter.kuwo.KuwoPlayerProfile
import com.jaco.musicenhance.adapter.salt.SaltPlayerProfile

/** Only explicitly registered packages receive hooks; unknown music apps remain untouched. */
internal object MusicAppRegistry {
    val profiles: List<MusicAppProfile> = listOf(QQPlayerProfile, ApplePlayerProfile, KugouLitePlayerProfile, KuwoPlayerProfile, KugouPlayerProfile, SaltPlayerProfile)

    fun find(packageName: String?): MusicAppProfile? = profiles.firstOrNull { it.packageName == packageName }

    fun forActivity(packageName: String?, className: String?): MusicAppProfile? =
        find(packageName)?.takeIf { it.ownsActivity(className) }
}

internal fun isHorizontalPlayerActivityName(packageName: String?, className: String?): Boolean =
    MusicAppRegistry.forActivity(packageName, className)?.isHorizontalPlayerActivity(className) == true
