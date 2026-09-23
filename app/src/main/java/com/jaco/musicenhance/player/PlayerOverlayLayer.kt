package com.jaco.musicenhance.player

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/** Keeps the player above content inserted later by the host, without detaching its controller. */
internal class PlayerOverlayLayer(
    private val decor: ViewGroup,
    private val player: View,
    private val onReordered: () -> Unit = {},
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private var observer: ViewTreeObserver? = null

    fun start() {
        player.addOnAttachStateChangeListener(this)
        refresh()
    }

    fun refresh() {
        if (player.isAttachedToWindow) observe()
        ensureOnTop()
        layoutInReadyWindow()
    }

    fun stop() {
        player.removeOnAttachStateChangeListener(this)
        removeObserver()
        observer = null
    }

    override fun onViewAttachedToWindow(view: View) = observe()

    override fun onViewDetachedFromWindow(view: View) = stop()

    override fun onPreDraw(): Boolean {
        if (ensureOnTop()) onReordered()
        layoutInReadyWindow()
        return true
    }

    private fun layoutInReadyWindow() {
        if (player.parent !== decor || decor.width <= 0 || decor.height <= 0) return
        if (player.width == decor.width && player.height == decor.height) return
        // Cover detection can first succeed during pre-draw, after the host's layout pass.
        // This full-window overlay must have bounds in that same frame. Do not cancel the
        // host draw or wait for another traversal, which would expose its native player.
        player.measure(
            View.MeasureSpec.makeMeasureSpec(decor.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(decor.height, View.MeasureSpec.EXACTLY),
        )
        player.layout(0, 0, decor.width, decor.height)
    }

    private fun observe() {
        val current = decor.viewTreeObserver
        if (observer === current) return
        removeObserver()
        observer = current
        current.addOnPreDrawListener(this)
    }

    private fun removeObserver() {
        val registered = observer ?: return
        val current = if (registered.isAlive) registered else decor.viewTreeObserver
        if (current.isAlive) current.removeOnPreDrawListener(this)
    }

    private fun ensureOnTop(): Boolean {
        if (player.parent !== decor) return false
        var changed = false
        var highestSiblingZ = 0f
        for (index in 0 until decor.childCount) {
            val sibling = decor.getChildAt(index)
            if (sibling !== player) highestSiblingZ = maxOf(highestSiblingZ, sibling.z)
        }
        if (player.z < highestSiblingZ) {
            player.translationZ = highestSiblingZ - player.elevation
            changed = true
        }
        if (decor.indexOfChild(player) != decor.childCount - 1) {
            decor.bringChildToFront(player)
            changed = true
        }
        return changed
    }
}
