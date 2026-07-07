package to.spora.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import to.spora.android.R

// IBM Plex Sans (display + body) + IBM Plex Mono (labels + data), bundled as
// assets: full Cyrillic incl. the be-specific Ў ў І і, and no runtime font
// fetch on a censored/GMS-less first launch (spec §3.3.1).
val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_bold, FontWeight.Bold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

// Role mapping (design token → M3 slot). Micro-labels and statuses are mono
// and written already-uppercase in string resources.
val Typography = Typography(
    // role: stat — big numbers (reserved for the future stats header)
    displayLarge = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 32.sp,
        letterSpacing = (-1).sp,
    ),
    // role: name — connection names, screen titles
    displayMedium = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.2).sp,
    ),
    // role: display — hero titles, modal titles
    headlineLarge = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.5).sp,
    ),
    // role: input text
    headlineMedium = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    // role: wordmark — SPORA is always typeset, never drawn
    titleMedium = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 13.sp,
        letterSpacing = 2.4.sp,
    ),
    // role: button label
    titleSmall = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.sp,
    ),
    // role: body
    bodyLarge = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 21.sp,
    ),
    // role: body small — hints, helpers, empty-state copy
    bodyMedium = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 17.sp,
    ),
    // role: micro label (mono, uppercase in source)
    labelSmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 9.5.sp,
        lineHeight = 12.sp,
        letterSpacing = 1.4.sp,
    ),
    // role: status (mono, uppercase in source)
    labelMedium = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 0.6.sp,
    ),
    // role: data — URLs, timestamps
    labelLarge = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)
