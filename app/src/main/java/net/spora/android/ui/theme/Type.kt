package net.spora.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import net.spora.android.R

val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

val chakraPetchFont = GoogleFont("Chakra Petch")

val ChakraPetchFamily = FontFamily(
    Font(googleFont = chakraPetchFont, fontProvider = fontProvider, weight = FontWeight.Light),
    Font(googleFont = chakraPetchFont, fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = chakraPetchFont, fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = chakraPetchFont, fontProvider = fontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = chakraPetchFont, fontProvider = fontProvider, weight = FontWeight.Bold),
)

val Typography = Typography(
    // Large display value: "142.5 GB" header
    displayLarge = TextStyle(
        fontFamily = ChakraPetchFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        letterSpacing = (-1).sp,
        lineHeight = 32.sp,
    ),
    // Medium display value: connection name in list
    displayMedium = TextStyle(
        fontFamily = ChakraPetchFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = (-0.5).sp,
    ),
    // Action card title: "New Connection"
    headlineLarge = TextStyle(
        fontFamily = ChakraPetchFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = (-1).sp,
    ),
    // App name "SPORA" in header
    titleMedium = TextStyle(
        fontFamily = ChakraPetchFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 1.sp,
    ),
    // Primary button text
    titleSmall = TextStyle(
        fontFamily = ChakraPetchFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    // Body text
    bodyLarge = TextStyle(
        fontFamily = ChakraPetchFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = ChakraPetchFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    // Small uppercase labels: "TOTAL TRAFFIC SHARED", "JUNE", etc.
    labelSmall = TextStyle(
        fontFamily = ChakraPetchFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
    ),
    // Nav item labels
    labelMedium = TextStyle(
        fontFamily = ChakraPetchFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
    ),
    // Input field text
    headlineMedium = TextStyle(
        fontFamily = ChakraPetchFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
    ),
)
