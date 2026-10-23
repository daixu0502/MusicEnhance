package vip.chener.musicenhance.adapter

import vip.chener.musicenhance.adapter.qq.QQPlayerProfile
import vip.chener.musicenhance.adapter.apple.ApplePlayerProfile
import vip.chener.musicenhance.adapter.kugoulite.KugouLitePlayerProfile
import vip.chener.musicenhance.adapter.kugou.KugouPlayerProfile
import vip.chener.musicenhance.adapter.kuwo.KuwoPlayerProfile
import vip.chener.musicenhance.adapter.salt.SaltPlayerProfile
import vip.chener.musicenhance.adapter.lxx.LxxPlayerProfile
import vip.chener.musicenhance.adapter.spotify.SpotifyPlayerProfile

/** Only explicitly registered packages receive hooks; unknown music apps remain untouched. */
internal object MusicAppRegistry {
    val profiles: List<MusicAppProfile> = listOf(QQPlayerProfile, ApplePlayerProfile, KugouLitePlayerProfile, KuwoPlayerProfile, KugouPlayerProfile, SaltPlayerProfile, LxxPlayerProfile, SpotifyPlayerProfile)

    fun find(packageName: String?): MusicAppProfile? = profiles.firstOrNull { it.packageName == packageName }

    fun forActivity(packageName: String?, className: String?): MusicAppProfile? =
        find(packageName)?.takeIf { it.ownsActivity(className) }
}

internal fun isHorizontalPlayerActivityName(packageName: String?, className: String?): Boolean =
    MusicAppRegistry.forActivity(packageName, className)?.isHorizontalPlayerActivity(className) == true
