package com.jaco.musicenhance.hook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import com.jaco.musicenhance.adapter.MusicAppAdapters
import com.jaco.musicenhance.adapter.isHorizontalPlayerActivityName
import com.jaco.musicenhance.device.CoverScreenDetector
import com.jaco.musicenhance.player.audio.SpectrumEngine
import com.jaco.musicenhance.player.media.MediaSessionStore
import com.jaco.musicenhance.player.media.PlayerProcessBridge
import io.github.libxposed.api.XposedInterface.Hooker

internal object MusicAppHooks {
    fun install(processName: String) {
        module.log(Log.INFO, MusicEnhanceModule.TAG, "Installing music app hooks in $processName; build=${com.jaco.musicenhance.BuildConfig.VERSION_CODE}")
        safeHook("application process bridge") {
            module.installHook(
                Instrumentation::class.java.declaredMethod(
                    "callApplicationOnCreate",
                    Application::class.java,
                ),
                "musicenhance.application.create",
                after { chain ->
                    PlayerActivityLaunchHook.install(chain.thisObject as? Instrumentation)
                    (chain.args.firstOrNull() as? Application)?.let { application ->
                        MusicAppAdapters.onApplicationCreated(application)
                        PlayerProcessBridge.initialize(application)
                    }
                },
            )
        }
        installAudioSpectrumHooks()
        PlayerActivityLaunchHook.install()
        installHorizontalPlayerLaunchBlock()
        installPlayerLifecycleHooks()
        safeHook("activity pause cleanup") {
            module.installHook(
                Instrumentation::class.java.declaredMethod("callActivityOnPause", Activity::class.java),
                "musicenhance.activity.pause",
            ) { chain ->
                safeHook("player window pause") {
                    (chain.args.firstOrNull() as? Activity)?.let(PlayerActivityRouter::onPaused)
                }
                chain.proceed()
            }
        }
        safeHook("activity destroy cleanup") {
            module.installHook(
                Instrumentation::class.java.declaredMethod("callActivityOnDestroy", Activity::class.java),
                "musicenhance.activity.destroy",
                after { chain -> (chain.args.firstOrNull() as? Activity)?.let(PlayerActivityRouter::onDestroyed) },
            )
        }
        safeHook("MediaSession.setMetadata") {
            module.installHook(
                MediaSession::class.java.declaredMethod("setMetadata", MediaMetadata::class.java),
                "musicenhance.media.metadata",
                after { chain -> MediaSessionStore.connect(chain.thisObject as? MediaSession) },
            )
        }
        safeHook("MediaSession.setPlaybackState") {
            module.installHook(
                MediaSession::class.java.declaredMethod("setPlaybackState", PlaybackState::class.java),
                "musicenhance.media.playback",
                after { chain -> MediaSessionStore.connect(chain.thisObject as? MediaSession) },
            )
        }
        safeHook("MediaSession.setActive") {
            module.installHook(
                MediaSession::class.java.declaredMethod(
                    "setActive",
                    Boolean::class.javaPrimitiveType!!,
                ),
                "musicenhance.media.active",
                after { chain ->
                    if (chain.args.firstOrNull() == true) {
                        MediaSessionStore.connect(chain.thisObject as? MediaSession)
                    }
                },
            )
        }

        safeHook("activity resume") {
            module.installHook(
                Instrumentation::class.java.declaredMethod(
                    "callActivityOnResume",
                    Activity::class.java,
                ),
                "musicenhance.activity.resume",
                after { chain ->
                    val activity = chain.args.firstOrNull() as? Activity ?: return@after
                    val displayId = runCatching { activity.display?.displayId }.getOrNull()
                    module.log(
                        Log.INFO,
                        MusicEnhanceModule.TAG,
                        "Activity resumed; process=$processName, activity=${activity.javaClass.name}, displayId=$displayId",
                    )
                    PlayerActivityRouter.onResumed(activity)
                },
            )
        }
    }

    private fun installAudioSpectrumHooks() {
        AudioTrack::class.java.declaredMethods
            .filter { method ->
                method.name == "write" && method.parameterTypes.firstOrNull()?.let { type ->
                    type == ByteArray::class.java ||
                        type == ShortArray::class.java ||
                        type == FloatArray::class.java ||
                        type == java.nio.ByteBuffer::class.java
                } == true
            }
            .forEachIndexed { index, method ->
                safeHook("AudioTrack.write[$index]") {
                    method.isAccessible = true
                    module.installHook(
                        method,
                        "musicenhance.audio.write.$index",
                    ) { chain ->
                        val outermostWrite = SpectrumEngine.enterAudioWrite()
                        try {
                            if (outermostWrite) {
                                runCatching {
                                    SpectrumEngine.capture(chain.thisObject as? AudioTrack, chain.args)
                                }
                            }
                            chain.proceed()
                        } finally {
                            SpectrumEngine.exitAudioWrite()
                        }
                    }
                }
            }
    }

    /**
     * QQ Music opens a landscape-only activity while the phone passes through 90掳/270掳.
     * Even when that activity is immediately finished, HyperOS applies a fixed-rotation
     * transform first and can leave the cover cutout in the opposite corner. Block the launch
     * while already on the cover display so the system only performs the 0掳/180掳 rotation.
     */
    private fun installHorizontalPlayerLaunchBlock() {
        val activityLaunchMethods = Activity::class.java.declaredMethods
            .filter { method ->
                method.name in setOf("startActivity", "startActivityForResult") &&
                    method.parameterTypes.firstOrNull() == Intent::class.java
            }
        activityLaunchMethods.forEachIndexed { index, method ->
            safeHook("horizontal Activity launch block[$index]") {
                method.isAccessible = true
                module.installHook(
                    method,
                    "musicenhance.horizontal.activity.launch.$index",
                ) { chain ->
                    val activity = chain.thisObject as? Activity
                    val intent = chain.args.firstOrNull() as? Intent
                    if (shouldBlockHorizontalLaunch(activity, intent)) null else chain.proceed()
                }
            }
        }

        // This catches ActivityResultLauncher and framework paths that go directly through
        // Instrumentation instead of calling an Activity start method visible above.
        val instrumentationLaunchMethods = Instrumentation::class.java.declaredMethods
            .filter { method ->
                method.name == "execStartActivity" &&
                    method.parameterTypes.any { it == Intent::class.java }
            }
        instrumentationLaunchMethods.forEachIndexed { index, method ->
            safeHook("horizontal Instrumentation launch block[$index]") {
                method.isAccessible = true
                module.installHook(
                    method,
                    "musicenhance.horizontal.instrumentation.launch.$index",
                ) { chain ->
                    val activity = chain.args.filterIsInstance<Activity>().firstOrNull()
                    val intent = chain.args.filterIsInstance<Intent>().firstOrNull()
                    if (shouldBlockHorizontalLaunch(activity, intent)) null else chain.proceed()
                }
            }
        }
        moduleInfo(
            "music app horizontal launch block ready; " +
                "activity=${activityLaunchMethods.size}, instrumentation=${instrumentationLaunchMethods.size}",
        )
    }

    private fun shouldBlockHorizontalLaunch(activity: Activity?, intent: Intent?): Boolean {
        if (activity == null || intent == null) return false
        val target = intent.component ?: runCatching {
            intent.resolveActivity(activity.packageManager)
        }.getOrNull()
        if (!isHorizontalPlayerActivityName(target?.packageName, target?.className)) return false
        if (!CoverScreenDetector.isCoverScreen(activity)) return false
        moduleInfo("Blocked music app horizontal player launch on cover screen")
        return true
    }

    private fun installPlayerLifecycleHooks() {
        Instrumentation::class.java.declaredMethods
            .filter { method ->
                method.name == "callActivityOnCreate" &&
                    method.parameterTypes.firstOrNull() == Activity::class.java
            }
            .forEachIndexed { index, method ->
                safeHook("player portrait orientation[$index]") {
                    method.isAccessible = true
                    module.installHook(
                        method,
                        "musicenhance.activity.create.$index",
                    ) { chain ->
                        val activity = chain.args.firstOrNull() as? Activity
                        val result = chain.proceed()
                        safeHook("player window created") {
                            activity?.let(PlayerActivityRouter::onCreated)
                        }
                        result
                    }
                }
            }
    }

    private fun after(block: (io.github.libxposed.api.XposedInterface.Chain) -> Unit): Hooker =
        Hooker { chain ->
            val result = chain.proceed()
            // A missing host API must not turn a completed Android lifecycle call into a crash.
            safeHook("music app callback") { block(chain) }
            result
        }
}
