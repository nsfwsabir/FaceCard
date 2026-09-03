package app.facecard.ui.theme

import androidx.compose.ui.graphics.Color

// FaceCard brand tokens (design.md §2). Dynamic colour on API 31+,
// these seeds are the fallback so API 26 looks intentional.
val FaceCardSeed = Color(0xFF7C4DFF)

val LightPrimary = Color(0xFF6750A4)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFEADDFF)
val LightOnPrimaryContainer = Color(0xFF4F378B)
val LightTertiary = Color(0xFFC00167)

val DarkPrimary = Color(0xFFD0BCFF)
val DarkOnPrimary = Color(0xFF381E72)
val DarkPrimaryContainer = Color(0xFF4F378B)
val DarkTertiary = Color(0xFFFFB1C8)

// Collage canvas stays dark-plum in both modes (export-consistent).
val CollageBgTop = Color(0xFF1B1025)
val CollageBgBottom = Color(0xFF3B1D5A)
