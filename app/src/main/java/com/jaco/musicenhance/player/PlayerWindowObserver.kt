package com.jaco.musicenhance.player

import android.view.View
import android.view.ViewTreeObserver

/** Reconcile an active window before drawing, after its real display bounds are available. */
internal class PlayerWindowObserver(
    private val decor: View,
    private val update: () -> Unit,
) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener, ViewTreeObserver.OnPreDrawListener {
    private var active = false
    private var pendingObserver: ViewTreeObserver? = null

    fun start() {
        if (!active) {
            active = true
            decor.addOnAttachStateChangeListener(this)
            decor.addOnLayoutChangeListener(this)
        }
        update()
        scheduleBeforeDraw()
    }

    fun stop() {
        active = false
        cancelBeforeDraw()
        decor.removeOnAttachStateChangeListener(this)
        decor.removeOnLayoutChangeListener(this)
    }

    override fun onViewAttachedToWindow(view: View) = scheduleBeforeDraw()

    override fun onViewDetachedFromWindow(view: View) = cancelBeforeDraw()

    override fun onLayoutChange(
        view: View, left: Int, top: Int, right: Int, bottom: Int,
        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
    ) {
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            scheduleBeforeDraw()
        }
    }

    override fun onPreDraw(): Boolean {
        cancelBeforeDraw()
        if (!active) return true
        update()
        // Host animations may request layout on every frame. Never freeze their surface.
        return true
    }

    private fun scheduleBeforeDraw() {
        if (!active) return
        val observer = decor.viewTreeObserver
        if (pendingObserver === observer) return
        cancelBeforeDraw()
        if (observer.isAlive) {
            pendingObserver = observer
            observer.addOnPreDrawListener(this)
        }
    }

    private fun cancelBeforeDraw() {
        val registered = pendingObserver ?: return
        // Android merges the unattached view's observer into the window observer on attach.
        // The old observer then dies, but its listener still lives in the new observer.
        val current = if (registered.isAlive) registered else decor.viewTreeObserver
        if (current.isAlive) current.removeOnPreDrawListener(this)
        pendingObserver = null
    }
}
