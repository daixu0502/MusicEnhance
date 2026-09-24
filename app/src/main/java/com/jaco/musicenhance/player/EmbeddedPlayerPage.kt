package com.jaco.musicenhance.player

import android.view.ViewGroup
import java.lang.ref.WeakReference

/** A host page inside an Activity. Callbacks must not retain the Activity/Fragment strongly. */
internal data class EmbeddedPlayerPage(
    val identity: Any,
    val nativeRoot: WeakReference<ViewGroup>,
    val dismiss: () -> Boolean,
)
