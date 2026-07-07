package to.spora.android.ui.theme

import androidx.compose.animation.core.CubicBezierEasing

// Spora motion — calm and short.
val EaseStandard = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
const val DurationQuick = 150 // toggle thumb, press feedback
const val DurationStandard = 220 // state-change fades, tab transitions
const val PressScale = 0.98f // hero card / button active state
