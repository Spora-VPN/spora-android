package to.spora.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val SporaColorScheme = lightColorScheme(
    primary = Slate,
    onPrimary = TextLight,
    primaryContainer = SlateDark,
    onPrimaryContainer = TextLight,
    secondary = Orange,
    onSecondary = TextLight,
    secondaryContainer = OrangeHover,
    onSecondaryContainer = TextLight,
    tertiary = Yellow,
    onTertiary = TextMain,
    background = Sage,
    onBackground = TextMain,
    surface = Sage,
    onSurface = TextMain,
    surfaceVariant = SageDark,
    onSurfaceVariant = TextMuted,
    outline = Border,
    error = Orange,
    onError = TextLight,
)

@Composable
fun SporaTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SporaColorScheme,
        typography = Typography,
        content = content,
    )
}
