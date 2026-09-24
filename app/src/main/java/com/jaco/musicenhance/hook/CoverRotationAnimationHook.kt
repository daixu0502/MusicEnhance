package com.jaco.musicenhance.hook

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import com.jaco.musicenhance.adapter.MusicAppRegistry
import kotlin.math.max
import kotlin.math.min

/** Customize the system surface animation only while Shell builds a music app cover 180° transition. */
internal object CoverRotationAnimationHook {
    private val angle = ThreadLocal<Float>()

    // LSPosed hooks Shell internals only for supported Flip transitions; the caller uses safeHook.
    @SuppressLint("PrivateApi")
    fun install(loader: ClassLoader) {
        if (Build.DEVICE.lowercase() !in setOf("ruyi", "bixi")) return
        val handler = Class.forName("com.android.wm.shell.transition.DefaultTransitionHandler", false, loader)
        val infoType = Class.forName("android.window.TransitionInfo", false, loader)
        val changeType = Class.forName("android.window.TransitionInfo\$Change", false, loader)
        val changes = infoType.getMethod("getChanges")
        val startRotation = changeType.getMethod("getStartRotation")
        val endRotation = changeType.getMethod("getEndRotation")
        val startBounds = changeType.getMethod("getStartAbsBounds")
        val endBounds = changeType.getMethod("getEndAbsBounds")
        val taskInfo = changeType.getMethod("getTaskInfo")
        val methods = handler.declaredMethods.filter { it.name == "startRotationAnimation" }
        check(methods.isNotEmpty()) { "Shell rotation entry missing" }
        methods.forEachIndexed { index, method ->
            method.isAccessible = true
            val hintIndex = method.parameterTypes.indexOfFirst { it == Int::class.javaPrimitiveType }
            module.installHook(method, "musicenhance.shell.rotation.$index") { chain ->
                val oldAngle = angle.get()
                val match = runCatching {
                    val change = chain.args.firstOrNull { changeType.isInstance(it) } ?: return@runCatching null
                    val info = chain.args.firstOrNull { infoType.isInstance(it) } ?: return@runCatching null
                    val start = startRotation.invoke(change) as Int
                    val end = endRotation.invoke(change) as Int
                    val before = startBounds.invoke(change) as Rect
                    val after = endBounds.invoke(change) as Rect
                    val topTask = (changes.invoke(info) as List<*>).firstNotNullOfOrNull {
                        (taskInfo.invoke(it) as? ActivityManager.RunningTaskInfo)?.takeIf { task -> task.topActivity != null }
                    }
                    val top = topTask?.topActivity
                    val profile = MusicAppRegistry.find(top?.packageName) ?: return@runCatching null
                    if (!CoverRotationPolicy.matches(start, end, before.width(), before.height(),
                            after.width(), after.height(), top?.packageName, top?.className,
                            isEnhancedTask(topTask)) || !hookEnabled(profile)
                    ) return@runCatching null
                    if (start == 0) 180f else -180f
                }.getOrNull()
                if (match == null) angle.remove() else {
                    angle.set(match)
                    moduleInfo("music app cover system rotation: angle=$match, duration=${DURATION_MS}ms")
                }
                try {
                    if (match != null && hintIndex >= 0) {
                        // QQ can request CROSSFADE/JUMPCUT. Those branches bypass both 180° factories.
                        moduleInfo("music app cover rotation hint=${chain.args[hintIndex]} -> ROTATE")
                        chain.proceed(chain.args.toTypedArray().apply { this[hintIndex] = 0 })
                    } else chain.proceed()
                } finally {
                    if (oldAngle == null) angle.remove() else angle.set(oldAngle)
                }
            }
            module.deoptimize(method)
        }

        // HyperOS native animation factory. The AOSP resource path below covers its fallback.
        safeHook("HyperOS cover rotation factory") {
            val impl = Class.forName("com.android.wm.shell.common.transition.ScreenRotationAnimationImpl", false, loader)
            for (enter in listOf(true, false)) {
                val name = if (enter) "loadRotation180Enter" else "loadRotation180Exit"
                module.installHook(impl.declaredMethod(name), "musicenhance.shell.$name") { chain ->
                    angle.get()?.let {
                        moduleInfo("music app cover rotation factory=$name")
                        createAnimation(it, enter)
                    } ?: chain.proceed()
                }
            }
        }
        module.installHook(
            AnimationUtils::class.java.declaredMethod("loadAnimation", Context::class.java, Int::class.javaPrimitiveType!!),
            "musicenhance.shell.rotation.resources",
        ) { chain ->
            val degrees = angle.get()
            val context = chain.args[0] as? Context
            val name = if (degrees == null || context == null) null else runCatching {
                context.resources.getResourceEntryName(chain.args[1] as Int)
            }.getOrNull()
            if (degrees != null) moduleInfo("music app cover rotation resource=$name")
            when (name) {
                "screen_rotate_180_enter" -> createAnimation(degrees!!, true)
                "screen_rotate_180_exit" -> createAnimation(degrees!!, false)
                else -> chain.proceed()
            }
        }
        safeHook("cover rotation surface verification") {
            val animator = Class.forName("com.android.wm.shell.transition.DefaultSurfaceAnimator", false, loader)
            animator.declaredMethods.filter { it.name == "buildWindowAnimation" }.forEach {
                module.deoptimize(it)
            }
            animator.declaredMethods.filter { it.name == "buildSurfaceAnimation" }.forEachIndexed { index, method ->
                module.installHook(method, "musicenhance.shell.rotation.surface.$index") { chain ->
                    if (angle.get() != null) {
                        val animation = chain.args.firstOrNull { it is Animation } as? Animation
                        moduleInfo("music app cover rotation surface: custom=${animation is CoverAnimation}, " +
                            "duration=${animation?.computeDurationHint()}, animation=${animation?.javaClass?.simpleName}")
                    }
                    chain.proceed()
                }
            }
        }
        moduleInfo("music app cover 180-degree system animation hooks ready")
    }

    private class CoverAnimation : AnimationSet(false)

    private fun isEnhancedTask(task: ActivityManager.RunningTaskInfo?): Boolean = runCatching {
        val info = task?.javaClass?.getField("topActivityInfo")?.get(task) as? android.content.pm.ActivityInfo
        info?.metaData?.getBoolean(com.jaco.musicenhance.player.PlayerActivitySessions.OWNED_ACTIVITY_METADATA) == true
    }.getOrDefault(false)

    private fun createAnimation(degrees: Float, enter: Boolean): Animation = CoverAnimation().apply {
        addAnimation(RotateAnimation(
            if (enter) degrees else 0f,
            if (enter) 0f else -degrees,
            Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f,
        ).apply {
            duration = DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            fillBefore = true
            fillAfter = true
            isFillEnabled = true
        })
        if (!enter) addAnimation(AlphaAnimation(1f, 0f).apply {
            startOffset = 240L
            duration = 260L
            interpolator = LinearInterpolator()
            fillBefore = true
            fillAfter = true
            isFillEnabled = true
        })
        fillBefore = true
        fillAfter = true
        isFillEnabled = true
    }

    private const val DURATION_MS = 620L
}

internal object CoverRotationPolicy {
    fun matches(start: Int, end: Int, startW: Int, startH: Int, endW: Int, endH: Int,
                packageName: String?, activityName: String?, enhancedActivity: Boolean = false): Boolean {
        fun cover(w: Int, h: Int) = min(w, h) > 0 && min(w, h).toFloat() / max(w, h) >= 0.60f
        return start in setOf(0, 2) && end in setOf(0, 2) && start != end &&
            cover(startW, startH) && cover(endW, endH) &&
            MusicAppRegistry.find(packageName)?.let {
                (enhancedActivity && it.ownsActivity(activityName)) ||
                    (it.isPlayerActivity(activityName) && !it.isHorizontalPlayerActivity(activityName))
            } == true
    }
}
