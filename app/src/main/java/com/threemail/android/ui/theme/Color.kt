package com.threemail.android.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette - indigo/violet primary with a fresh sky secondary and teal accent.
val Primary = Color(0xFF5B54E6)
val OnPrimary = Color(0xFFFFFFFF)
val PrimaryContainer = Color(0xFFE4E1FF)
val OnPrimaryContainer = Color(0xFF16107A)
val Secondary = Color(0xFF0EA5E9)
val OnSecondary = Color(0xFFFFFFFF)
val SecondaryContainer = Color(0xFFCDEBFF)
val OnSecondaryContainer = Color(0xFF06304A)
val Tertiary = Color(0xFF14B8A6)
val OnTertiary = Color(0xFFFFFFFF)
val Error = Color(0xFFEF4444)
val OnError = Color(0xFFFFFFFF)
val ErrorContainer = Color(0xFFFFDAD6)
val OnErrorContainer = Color(0xFF410002)
val Success = Color(0xFF22C55E)
val Warning = Color(0xFFEAB308)

val BackgroundLight = Color(0xFFF6F7FB)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFEEEFF5)
val OnSurfaceLight = Color(0xFF11131A)
val OnSurfaceVariantLight = Color(0xFF5B616E)
val OutlineLight = Color(0xFFD3D6E0)

val BackgroundDark = Color(0xFF0B0D14)
val SurfaceDark = Color(0xFF161923)
val SurfaceVariantDark = Color(0xFF232733)
val OnSurfaceDark = Color(0xFFF3F4F8)
val OnSurfaceVariantDark = Color(0xFF9BA1B0)
val OutlineDark = Color(0xFF343947)

// Deterministic palette for sender avatars.
val AvatarColors = listOf(
    Color(0xFF5B54E6),
    Color(0xFF0EA5E9),
    Color(0xFF14B8A6),
    Color(0xFFF97316),
    Color(0xFFEC4899),
    Color(0xFF8B5CF6),
    Color(0xFF22C55E),
    Color(0xFFEAB308),
    Color(0xFFEF4444),
    Color(0xFF06B6D4)
)

fun avatarColorFor(key: String): Color {
    if (key.isEmpty()) return AvatarColors.first()
    val index = (key.hashCode() and 0x7FFFFFFF) % AvatarColors.size
    return AvatarColors[index]
}
