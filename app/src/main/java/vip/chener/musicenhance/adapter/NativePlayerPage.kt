package vip.chener.musicenhance.adapter

/** Navigation recovery only; data providers never depend on this page or its View tree. */
internal data class NativePlayerPage(
    val identity: Any,
    val onLaunchFailed: (() -> Unit)? = null,
    val dismiss: () -> Boolean,
)
