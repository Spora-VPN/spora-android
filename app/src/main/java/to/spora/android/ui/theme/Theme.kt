package to.spora.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val SporaColorScheme = lightColorScheme(
    primary = ColorPrimary,
    onPrimary = TextOnDark,
    primaryContainer = Pine900,
    onPrimaryContainer = TextOnDark,
    secondary = ColorAction,
    onSecondary = OnActionTitle,
    secondaryContainer = ColorAction,
    onSecondaryContainer = OnActionTitle,
    tertiary = ColorPending,
    onTertiary = ColorPendingText,
    background = SurfaceBg,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = Sage400,
    onSurfaceVariant = TextMuted,
    outline = BorderHairline,
    error = ColorError,
    onError = Sage100,
)

// Corner radii — the "Instrument" scale: hero 16 / card+button 10 / chip 6 /
// tag 4 (nav 20 and toggle 3/1 are set at the component).
private val SporaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
fun SporaTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SporaColorScheme,
        typography = Typography,
        shapes = SporaShapes,
        content = content,
    )
}
