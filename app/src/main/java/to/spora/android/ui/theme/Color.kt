package to.spora.android.ui.theme

import androidx.compose.ui.graphics.Color

// Spora color tokens — locked direction "Instrument" (design canvas 3b).
// Light theme only for now; dark theme is designed headroom, not shipped.

// ── base scales ─────────────────────────────────────────────
val Sage100 = Color(0xFFF6F8F4) // card
val Sage200 = Color(0xFFEFF2EE) // text/thumb on dark
val Sage300 = Color(0xFFD6DAD3) // app background
val Sage400 = Color(0xFFC2C7C1) // toggle track off
val Ink900 = Color(0xFF1D211D) // primary text
val Ink600 = Color(0xFF494F49) // muted text (AA on sage-300)
val Pine800 = Color(0xFF333B36) // primary UI: buttons, toggle-on
val Pine900 = Color(0xFF272E29) // bottom nav, dark chrome
val Soil700 = Color(0xFF3E4A40) // mark arc (recolor B), quiet accent
val Green500 = Color(0xFF6C9044) // THE mark green — semantic success family root
val Green600 = Color(0xFF587536) // success dot on light (≥3:1)
val Green700 = Color(0xFF4E6B2F) // success text on light (≥4.5:1)
val Ember500 = Color(0xFFDB5F3D) // action orange — hero cards, big affirmative
val Ember050 = Color(0xFFFFF4EE) // titles on action
val Ember900 = Color(0xFF38180D) // micro-labels on action (AA)
val Gold500 = Color(0xFFDAC35B) // pending fill
val Gold700 = Color(0xFF8A6D14) // pending text/dot-ring
val Rust600 = Color(0xFFAC3322) // error
val Rust800 = Color(0xFF7E2013) // error emphasis

// ── semantic aliases ────────────────────────────────────────
val SurfaceBg = Sage300
val SurfaceCard = Sage100
val SurfaceNav = Pine900
val SurfaceErrorTint = Rust600.copy(alpha = 0.08f)

val TextPrimary = Ink900
val TextMuted = Ink600
val TextOnDark = Sage200
val TextOnDarkMuted = Color(0xFF99A29A)

val ColorPrimary = Pine800
val ColorAction = Ember500
val OnActionTitle = Ember050
val OnActionSubtitle = Color(0xD9FFF4EE) // rgba(255,244,238,0.85)
val OnActionMicro = Ember900
val ColorSuccess = Green500
val ColorSuccessDot = Green600
val ColorSuccessText = Green700
val ColorPending = Gold500
val ColorPendingText = Gold700
val ColorError = Rust600
val ColorErrorStrong = Rust800
val ColorWaiting = Ink600 // waiting-on-a-person is neutral, dashed

val BorderHairline = Color(0xFF1C201C).copy(alpha = 0.15f)
val BorderDivider = Color(0xFF1C201C).copy(alpha = 0.10f)
val BorderStrong = Color(0xFF1C201C).copy(alpha = 0.35f)
val BorderError = Rust600.copy(alpha = 0.5f)
