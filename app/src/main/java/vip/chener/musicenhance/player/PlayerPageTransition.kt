package vip.chener.musicenhance.player

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import vip.chener.musicenhance.hook.moduleInfo

/** Callback-free property animations use RenderThread, not the host's busy main-thread frame loop. */
internal class PlayerPageTransition(
    private val content: View,
    private val canAnimate: () -> Boolean = { true },
) {
    private val handler = Handler(Looper.getMainLooper())
    private var observer: ViewTreeObserver? = null
    private var readyDeadlineMs = 0L
    private var lastWidth = 0
    private var lastHeight = 0
    private var onExitFinished: (() -> Unit)? = null
    private val completion = Runnable {
        val callback = onExitFinished
        onExitFinished = null
        callback?.invoke()
    }
    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        val stable = content.width == lastWidth && content.height == lastHeight && content.height > 0
        lastWidth = content.width
        lastHeight = content.height
        when {
            stable && canAnimate() -> {
                cancelBeforeDraw()
                content.translationY = content.height.toFloat()
                content.alpha = 1f
                moduleInfo("Player slide enter: size=${content.width}x${content.height}, scale=${ValueAnimator.getDurationScale()}")
                animateTo(0f, ENTER_DURATION_MS)
            }
            SystemClock.uptimeMillis() >= readyDeadlineMs -> {
                moduleInfo("Player slide skipped: layout/background not ready; size=${content.width}x${content.height}")
                completeExit()
            }
            else -> content.postInvalidateOnAnimation()
        }
        true
    }

    fun enter() {
        cancelAnimation()
        if (!ValueAnimator.areAnimatorsEnabled()) return
        content.alpha = 0f
        readyDeadlineMs = SystemClock.uptimeMillis() + LAYOUT_WAIT_MS
        observer = content.viewTreeObserver.also { it.addOnPreDrawListener(beforeDraw) }
        content.postInvalidateOnAnimation()
    }

    fun exit(onFinished: () -> Unit) {
        cancelAnimation()
        onExitFinished = onFinished
        if (content.alpha == 0f || !content.isLaidOut || !content.isAttachedToWindow ||
            !canAnimate() || !ValueAnimator.areAnimatorsEnabled()) {
            completeExit()
            return
        }
        animateTo(content.height.toFloat(), EXIT_DURATION_MS)
        moduleInfo("Player slide exit: size=${content.width}x${content.height}, scale=${ValueAnimator.getDurationScale()}")
        // Animator listeners, update callbacks, withLayer and withEndAction all prevent the
        // RenderThread implementation. Only Activity completion is scheduled on main.
        handler.postDelayed(completion, (EXIT_DURATION_MS * ValueAnimator.getDurationScale()).toLong() + FRAME_MARGIN_MS)
    }

    fun completeExit() {
        cancelAnimation()
        content.alpha = 1f
        content.translationY = 0f
        completion.run()
    }

    fun release() {
        onExitFinished = null
        completeExit()
    }

    private fun animateTo(targetY: Float, durationMs: Long) {
        content.animate().translationY(targetY).setDuration(durationMs).setStartDelay(0)
            .setInterpolator(PathInterpolator(0.22f, 0f, 0.18f, 1f)).start()
    }

    private fun cancelAnimation() {
        cancelBeforeDraw()
        handler.removeCallbacks(completion)
        content.animate().cancel()
    }

    private fun cancelBeforeDraw() {
        val registered = observer ?: return
        observer = null
        val current = if (registered.isAlive) registered else content.viewTreeObserver
        if (current.isAlive) current.removeOnPreDrawListener(beforeDraw)
    }

    private companion object {
        const val ENTER_DURATION_MS = 360L
        const val EXIT_DURATION_MS = 280L
        const val LAYOUT_WAIT_MS = 350L
        const val FRAME_MARGIN_MS = 32L
    }
}
