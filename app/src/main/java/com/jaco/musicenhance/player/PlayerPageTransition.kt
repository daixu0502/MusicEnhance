package com.jaco.musicenhance.player

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw

/** Animate in window coordinates, so “bottom” follows both supported screen orientations. */
internal class PlayerPageTransition(private val content: View) {
    private var pendingEnter: OneShotPreDrawListener? = null
    private var animator: ValueAnimator? = null
    private var offsetFraction = 0f
    private var onExitFinished: (() -> Unit)? = null

    fun enter() {
        pendingEnter = content.doOnPreDraw {
            pendingEnter = null
            if (ValueAnimator.areAnimatorsEnabled()) {
                offsetFraction = 1f
                content.translationY = content.height.toFloat()
                animateTo(0f, ENTER_DURATION_MS)
            }
        }
    }

    fun exit(onFinished: () -> Unit) {
        cancelAnimation()
        onExitFinished = onFinished
        if (!content.isLaidOut || !content.isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            completeExit()
            return
        }
        animateTo(1f, EXIT_DURATION_MS)
    }

    /** A stopped/destroyed window must never wait for another animation frame to finish. */
    fun completeExit() {
        cancelAnimation()
        val completion = onExitFinished
        onExitFinished = null
        if (completion == null) {
            offsetFraction = 0f
            content.translationY = 0f
        }
        completion?.invoke()
    }

    fun release() {
        cancelAnimation()
        onExitFinished = null
        content.translationY = 0f
    }

    private fun animateTo(target: Float, durationMs: Long) {
        animator = ValueAnimator.ofFloat(offsetFraction, target).apply {
            duration = durationMs
            interpolator = PathInterpolator(0.22f, 0.0f, 0.18f, 1.0f)
            addUpdateListener {
                offsetFraction = it.animatedValue as Float
                // Read the latest size if fullscreen bounds or orientation change mid-animation.
                content.translationY = content.height * offsetFraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    if (target == 1f) completeExit()
                }
            })
            start()
        }
    }

    private fun cancelAnimation() {
        pendingEnter?.removeListener()
        pendingEnter = null
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
    }

    private companion object {
        const val ENTER_DURATION_MS = 360L
        const val EXIT_DURATION_MS = 280L
    }
}
