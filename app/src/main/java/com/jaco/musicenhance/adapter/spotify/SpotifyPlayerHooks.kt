package com.jaco.musicenhance.adapter.spotify

import android.app.PendingIntent
import android.app.Activity
import android.media.session.MediaSession
import android.os.Bundle
import android.os.Handler
import android.view.ViewGroup
import com.jaco.musicenhance.adapter.NativePlayerPage
import com.jaco.musicenhance.hook.PlayerActivityRouter
import com.jaco.musicenhance.hook.module
import com.jaco.musicenhance.hook.moduleInfo
import com.jaco.musicenhance.hook.safeHook
import java.lang.ref.WeakReference

internal object SpotifyPlayerHooks {
    @Volatile var mediaSession = WeakReference<MediaSession>(null)
    @Volatile var lyricsService = WeakReference<Any>(null)
    data class FavoriteAction(val uri: String, val favorite: Boolean, val intent: PendingIntent)
    @Volatile var addFavorite: FavoriteAction? = null
    @Volatile var removeFavorite: FavoriteAction? = null

    fun install(loader: ClassLoader) {
        safeHook("Spotify native player lifecycle") {
            val type = loader.loadClass("com.spotify.nowplaying.musicinstallation.NowPlayingActivity")
            module.installHook(type.getDeclaredMethod("onCreate", Bundle::class.java), "musicenhance.spotify.page") { chain ->
                val result = chain.proceed()
                safeHook("Spotify player route") { (chain.thisObject as? Activity)?.let(::observe) }
                result
            }
        }
        safeHook("Spotify player state (9.1.90.2270)") {
            val state = loader.loadClass("com.spotify.player.model.AutoValue_PlayerState")
            state.getMethod("track")
            state.getMethod("options")
            val build = loader.loadClass("com.spotify.player.model.AutoValue_PlayerState\$Builder")
                .getDeclaredMethod("build")
            check(build.returnType.name == "com.spotify.player.model.PlayerState")
            module.installHook(build, "musicenhance.spotify.state") { chain ->
                chain.proceed().also { SpotifyApi.nativeState = it }
            }
        }
        safeHook("Spotify media session") {
            loader.loadClass("p.zpf0").getMethod("onCustomAction", String::class.java, Bundle::class.java)
            val method = MediaSession::class.java.getMethod("setCallback", MediaSession.Callback::class.java, Handler::class.java)
            module.installHook(method, "musicenhance.spotify.media") { chain ->
                val result = chain.proceed()
                if (chain.args[0]?.javaClass?.name == "p.zpf0") {
                    mediaSession = WeakReference(chain.thisObject as MediaSession)
                }
                result
            }
        }
        safeHook("Spotify favorite actions") {
            val method = loader.loadClass("p.yfr0").getMethod("a", Bundle::class.java, String::class.java)
            check(method.returnType == PendingIntent::class.java)
            module.installHook(method, "musicenhance.spotify.favorite") { chain ->
                val result = chain.proceed()
                val name = chain.args[1] as? String
                val favorite = when (name) {
                    "com.spotify.app.music.scopes.utils.action.player.NOTIFICATION_ADD_TO_COLLECTION" -> true
                    "com.spotify.app.music.scopes.utils.action.player.NOTIFICATION_REMOVE_FROM_COLLECTION" -> false
                    else -> null
                }
                if (favorite != null && result is PendingIntent) {
                    val uri = (chain.args[0] as? Bundle)?.getString("uri")
                    if (uri != null) {
                        val action = FavoriteAction(uri, favorite, result)
                        if (favorite) addFavorite = action else removeFavorite = action
                    }
                }
                result
            }
        }
        for (name in listOf("p.hjd0", "p.zod0")) safeHook("Spotify native lyrics service $name") {
            val type = loader.loadClass(name)
            check(type.getMethod("b", String::class.java, String::class.java).returnType.name == "io.reactivex.rxjava3.core.Single")
            module.installHook(type.declaredConstructors.single(), "musicenhance.spotify.lyrics.service.$name") { chain ->
                chain.proceed().also { lyricsService = WeakReference(chain.thisObject) }
            }
        }
        moduleInfo("Spotify adapter hooks inspected for 9.1.90.2270; native capabilities remain account dependent")
    }

    fun observe(activity: Activity) {
        if (!SpotifyPlayerProfile.isPlayerActivity(activity.javaClass.name)) return
        val root = activity.window.decorView as? ViewGroup ?: return
        val owner = WeakReference(activity)
        PlayerActivityRouter.onNativePageShown(activity, NativePlayerPage(
            System.identityHashCode(activity), WeakReference(root),
            dismiss = {
                val native = owner.get()
                if (native == null || native.isDestroyed || native.isFinishing) true else {
                    // Spotify.finish() otherwise defers finishing to its shared-element return transition.
                    // Close the hidden native page before the common enhanced Activity exposes the home.
                    native.intent?.putExtra("EXTRA_SHARED_ELEMENT_TRANSITION", false)
                    native.window.returnTransition = null
                    native.window.exitTransition = null
                    native.finish()
                    native.isFinishing
                }
            },
        ))
    }
}
