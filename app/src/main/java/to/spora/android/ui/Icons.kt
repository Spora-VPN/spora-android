package to.spora.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Bespoke minimal stroke set — 24px grid, stroke 1.8–2px, round caps/joins,
// geometry only. Direction is semantic: ↑ share (give), ↓ use (receive).
private fun strokeIcon(
    name: String,
    strokeWidth: Float = 1.8f,
    content: PathBuilder.() -> Unit,
): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) { content() }
    }.build()

object SporaIcons {
    // → (rotated −45° where the design asks for the circle-arrow)
    val ArrowRight: ImageVector by lazy {
        strokeIcon("ArrowRight", strokeWidth = 2f) {
            moveTo(5f, 12f)
            horizontalLineToRelative(14f)
            moveTo(12f, 5f)
            lineToRelative(7f, 7f)
            lineToRelative(-7f, 7f)
        }
    }

    // ↑ out of a tray — re-share an active link
    val ShareAgain: ImageVector by lazy {
        strokeIcon("ShareAgain") {
            moveTo(12f, 15f)
            verticalLineTo(4f)
            moveTo(8f, 7.5f)
            lineTo(12f, 3.5f)
            lineToRelative(4f, 4f)
            moveTo(5f, 13f)
            verticalLineToRelative(6.5f)
            horizontalLineToRelative(14f)
            verticalLineTo(13f)
        }
    }

    val Trash: ImageVector by lazy {
        strokeIcon("Trash") {
            moveTo(5f, 7f)
            horizontalLineToRelative(14f)
            moveTo(10f, 7f)
            verticalLineTo(5f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(2f)
            moveTo(7f, 7f)
            lineToRelative(0.9f, 12.5f)
            horizontalLineToRelative(8.2f)
            lineTo(17f, 7f)
        }
    }

    // Speech bubble — feedback
    val Feedback: ImageVector by lazy {
        strokeIcon("Feedback") {
            moveTo(20f, 6.5f)
            verticalLineToRelative(7f)
            arcToRelative(2.5f, 2.5f, 0f, false, true, -2.5f, 2.5f)
            horizontalLineTo(9.5f)
            lineTo(4f, 20f)
            verticalLineTo(6.5f)
            arcTo(2.5f, 2.5f, 0f, false, true, 6.5f, 4f)
            horizontalLineToRelative(11f)
            arcTo(2.5f, 2.5f, 0f, false, true, 20f, 6.5f)
        }
    }

    // ↑ — the Share tab
    val NavShare: ImageVector by lazy {
        strokeIcon("NavShare", strokeWidth = 2f) {
            moveTo(12f, 19f)
            verticalLineTo(5f)
            moveTo(6f, 11f)
            lineToRelative(6f, -6f)
            lineToRelative(6f, 6f)
        }
    }

    // ↓ — the Use tab
    val NavUse: ImageVector by lazy {
        strokeIcon("NavUse", strokeWidth = 2f) {
            moveTo(12f, 5f)
            verticalLineToRelative(14f)
            moveTo(6f, 13f)
            lineToRelative(6f, 6f)
            lineToRelative(6f, -6f)
        }
    }
}
