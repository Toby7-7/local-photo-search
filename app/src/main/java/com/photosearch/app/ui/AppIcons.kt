package com.photosearch.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AppIcons {

    val Pause: ImageVector = ImageVector.Builder(
        name = "Pause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 5f)
            lineTo(9f, 5f)
            lineTo(9f, 19f)
            lineTo(5f, 19f)
            close()
            moveTo(15f, 5f)
            lineTo(19f, 5f)
            lineTo(19f, 19f)
            lineTo(15f, 19f)
            close()
        }
    }.build()

    val PlayArrow: ImageVector = ImageVector.Builder(
        name = "PlayArrow",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 3f)
            lineTo(19f, 12f)
            lineTo(5f, 21f)
            close()
        }
    }.build()
}
