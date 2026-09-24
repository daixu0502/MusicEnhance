package com.jaco.musicenhance.adapter


/** App-specific identity and activity rules; intentionally independent of Android runtime types. */
internal data class MusicAppProfile(
    val packageName: String,
    val displayName: String,
    val enabledPreference: String,
    val homeActivityNames: Set<String>,
    private val playerActivityMatcher: (String) -> Boolean,
    private val horizontalActivityMatcher: (String) -> Boolean,
    private val activityNamespaces: Set<String> = setOf(packageName),
    val experimental: Boolean = false,
    val hideCoverHomeNavigationBar: Boolean = false,
    val embeddedPlayerActivityNames: Set<String> = emptySet(),
) {
    fun isHomeActivity(className: String?): Boolean = ownsActivity(className) && className in homeActivityNames

    fun isPlayerActivity(className: String?): Boolean =
        ownsActivity(className) && !isHomeActivity(className) && playerActivityMatcher(className!!)

    fun isHorizontalPlayerActivity(className: String?): Boolean =
        isPlayerActivity(className) && horizontalActivityMatcher(className!!)

    /** Window titles may use either a full class name or Android's package/.ShortClass form. */
    fun isPlayerWindow(title: String?): Boolean {
        title ?: return false
        if (isEmbeddedPlayerWindow(title)) return true
        if ('/' !in title) return isPlayerActivity(title)
        if (title.substringBefore('/') != packageName) return false
        val className = title.substringAfter('/')
        return isPlayerActivity(if (className.startsWith('.')) packageName + className else className)
    }

    fun isEmbeddedPlayerWindow(title: String?): Boolean =
        embeddedPlayerActivityNames.any { title == embeddedPlayerWindowTitle(it) }

    fun embeddedPlayerWindowTitle(className: String): String = "MusicEnhance:$packageName/$className"

    fun ownsActivity(className: String?): Boolean =
        className != null && activityNamespaces.any { className.startsWith("$it.") }
}
