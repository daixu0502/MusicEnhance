package com.jaco.musicenhance

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Matching rounded strokes; the navigation bar applies the theme's selected/unselected tint. */
internal object MainNavigationIcons {
    val information = icon("Information") {
        moveTo(7f, 4f); lineTo(17f, 4f); curveTo(19f, 4f, 20f, 5f, 20f, 7f)
        lineTo(20f, 17f); curveTo(20f, 19f, 19f, 20f, 17f, 20f)
        lineTo(7f, 20f); curveTo(5f, 20f, 4f, 19f, 4f, 17f)
        lineTo(4f, 7f); curveTo(4f, 5f, 5f, 4f, 7f, 4f); close()
        moveTo(8f, 8f); lineTo(16f, 8f)
        moveTo(8f, 12f); lineTo(16f, 12f)
        moveTo(8f, 16f); lineTo(13f, 16f)
    }
    val functions = icon("Functions") {
        moveTo(4f, 6f); lineTo(7f, 6f); moveTo(11f, 6f); lineTo(20f, 6f)
        moveTo(9f, 4f); lineTo(9f, 8f)
        moveTo(4f, 12f); lineTo(13f, 12f); moveTo(17f, 12f); lineTo(20f, 12f)
        moveTo(15f, 10f); lineTo(15f, 14f)
        moveTo(4f, 18f); lineTo(7f, 18f); moveTo(11f, 18f); lineTo(20f, 18f)
        moveTo(9f, 16f); lineTo(9f, 20f)
    }
    val about = icon("About") {
        moveTo(12f, 3f)
        curveTo(17f, 3f, 21f, 7f, 21f, 12f)
        curveTo(21f, 17f, 17f, 21f, 12f, 21f)
        curveTo(7f, 21f, 3f, 17f, 3f, 12f)
        curveTo(3f, 7f, 7f, 3f, 12f, 3f); close()
        moveTo(12f, 11f); lineTo(12f, 17f)
        moveTo(12f, 7.2f); lineTo(12f, 7.3f)
    }

    private fun icon(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()
}
