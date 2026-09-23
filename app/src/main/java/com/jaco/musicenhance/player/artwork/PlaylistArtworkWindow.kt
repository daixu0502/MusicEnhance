package com.jaco.musicenhance.player.artwork

/** Nearest neighbours first, with no repeated/current entries in short circular playlists. */
internal object PlaylistArtworkWindow {
    private const val RADIUS = 3

    fun indices(size: Int, currentIndex: Int, wrap: Boolean): List<Int> {
        if (currentIndex !in 0 until size) return emptyList()
        val result = linkedSetOf<Int>()
        for (distance in 1..RADIUS) {
            for (offset in intArrayOf(distance, -distance)) {
                val index = if (wrap) Math.floorMod(currentIndex + offset, size) else currentIndex + offset
                if (index in 0 until size && index != currentIndex) result += index
            }
        }
        return result.toList()
    }
}
