package vip.chener.musicenhance.adapter.spotify

import android.app.PendingIntent
import android.app.Activity
import android.media.session.MediaSession
import android.os.Bundle
import android.os.Handler
import vip.chener.musicenhance.adapter.NativePlayerPage
import vip.chener.musicenhance.hook.PlayerActivityRouter
import vip.chener.musicenhance.hook.module
import vip.chener.musicenhance.hook.moduleInfo
import vip.chener.musicenhance.hook.safeHook
import java.lang.ref.WeakReference
import java.lang.reflect.Executable
import java.util.WeakHashMap
import io.github.libxposed.api.XposedInterface

internal object SpotifyPlayerHooks {
    @Volatile var mediaSession = WeakReference<MediaSession>(null)
    @Volatile var lyricsService = WeakReference<Any>(null)
    @Volatile private var lyricsProvider = WeakReference<Any>(null)
    private val installed = mutableSetOf<Executable>()
    private val mediaCandidates = WeakHashMap<MediaSession, WeakReference<MediaSession.Callback>>()
    @Volatile private var callbackType: Class<*>? = null
    fun hasLyricsService() = lyricsService.get() != null || lyricsProvider.get() != null
    /** Called on the lyrics worker: resolve the host-selected v2/v3 service without a player page. */
    fun resolveLyricsService(): Any? = lyricsService.get() ?: lyricsProvider.get()?.let { provider ->
        provider.javaClass.getMethod("get").invoke(provider)?.also { lyricsService = WeakReference(it) }
    }
    data class FavoriteAction(val uri: String, val favorite: Boolean, val intent: PendingIntent)
    @Volatile var addFavorite: FavoriteAction? = null
    @Volatile var removeFavorite: FavoriteAction? = null

    fun install(loader: ClassLoader) {
        SpotifyAudioHooks.install(loader)
        safeHook("Spotify application lyrics provider") {
            // 9.1.90.2270 xzo case 1177 delegates to vbd0.q, retaining the host feature flags.
            // The application graph creates the provider even if NowPlayingActivity never opens.
            val constructor = SpotifyInterfaceRules.providerConstructor(loader)
            hook(constructor, "musicenhance.spotify.lyrics.provider") { chain ->
                chain.proceed().also {
                    if (chain.args[1] == 1177) lyricsProvider = WeakReference(chain.thisObject)
                }
            }
        }
        safeHook("Spotify native player lifecycle") {
            val type = loader.loadClass("com.spotify.nowplaying.musicinstallation.NowPlayingActivity")
            hook(type.getDeclaredMethod("onCreate", Bundle::class.java), "musicenhance.spotify.page") { chain ->
                val result = chain.proceed()
                safeHook("Spotify player route") { (chain.thisObject as? Activity)?.let(::observe) }
                result
            }
        }
        safeHook("Spotify player state (9.1.90.2270)") {
            val build = SpotifyInterfaceRules.stateBuilder(loader)
            hook(build, "musicenhance.spotify.state") { chain ->
                chain.proceed().also { SpotifyApi.nativeState = it }
            }
        }
        safeHook("Spotify media session capture") {
            val method = MediaSession::class.java.getMethod("setCallback", MediaSession.Callback::class.java, Handler::class.java)
            hook(method, "musicenhance.spotify.media") { chain ->
                val result = chain.proceed()
                val session = chain.thisObject as? MediaSession
                val callback = chain.args[0] as? MediaSession.Callback
                if (session != null) synchronized(mediaCandidates) {
                    if (callback == null) mediaCandidates.remove(session) else {
                        // Retain only weak observations until the background callback rule resolves.
                        if (mediaCandidates.size >= 4 && session !in mediaCandidates) mediaCandidates.clear()
                        mediaCandidates[session] = WeakReference(callback)
                        if (callback.javaClass == callbackType) mediaSession = WeakReference(session)
                    }
                }
                result
            }
        }
        safeHook("Spotify media callback contract") {
            callbackType = SpotifyInterfaceRules.callbackType(loader)
            synchronized(mediaCandidates) {
                mediaCandidates.entries.singleOrNull { it.value.get()?.javaClass == callbackType }
                    ?.let { mediaSession = WeakReference(it.key) }
            }
        }
        safeHook("Spotify favorite actions") {
            val method = SpotifyInterfaceRules.favoriteAction(loader)
            hook(method, "musicenhance.spotify.favorite") { chain ->
                val result = chain.proceed()
                val name = chain.args[1] as? String
                val favorite = when (name) {
                    SpotifyInterfaceRules.ADD_FAVORITE -> true
                    SpotifyInterfaceRules.REMOVE_FAVORITE -> false
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
        for (version in 2..3) safeHook("Spotify native lyrics service v$version") {
            val type = SpotifyInterfaceRules.lyricsService(loader, version)
            hook(type.declaredConstructors.single(), "musicenhance.spotify.lyrics.service.v$version") { chain ->
                chain.proceed().also { lyricsService = WeakReference(chain.thisObject) }
            }
        }
        moduleInfo("Spotify adapter hooks inspected; native capabilities remain account dependent")
    }

    private fun hook(executable: Executable, id: String, hooker: XposedInterface.Hooker) {
        if (executable in installed) return
        module.installHook(executable, id, hooker)
        installed.add(executable)
    }

    fun observe(activity: Activity) {
        if (!SpotifyPlayerProfile.isPlayerActivity(activity.javaClass.name)) return
        val owner = WeakReference(activity)
        PlayerActivityRouter.onNativePageShown(activity, NativePlayerPage(
            System.identityHashCode(activity),
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
