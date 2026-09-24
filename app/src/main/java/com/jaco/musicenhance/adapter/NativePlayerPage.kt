package com.jaco.musicenhance.adapter

import android.view.ViewGroup
import java.lang.ref.WeakReference

/** A host page inside an Activity. Callbacks must not retain the Activity/Fragment strongly. */
internal data class NativePlayerPage(
    val identity: Any,
    val nativeRoot: WeakReference<ViewGroup>,
    val onLaunchFailed: (() -> Unit)? = null,
    val dismiss: () -> Boolean,
)
