package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Shape system — asymmetric authority:
// Smaller elements are sharper; larger containers are more rounded.
// Avoids the "everything is equally pill-shaped" problem.
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),   // Chips, micro-badges
    small      = RoundedCornerShape(10.dp),  // Input fields, small buttons
    medium     = RoundedCornerShape(14.dp),  // Cards, bottom sheet header
    large      = RoundedCornerShape(20.dp),  // Modal sheets, large cards
    extraLarge = RoundedCornerShape(26.dp)   // Full-screen rounded containers
)

private val DarkColorScheme = darkColorScheme(
    primary            = GoldBase,
    onPrimary          = Color(0xFF18100A),
    primaryContainer   = Color(0xFF1A1200),
    onPrimaryContainer = GoldLight,

    secondary          = GoldLight,
    onSecondary        = Color(0xFF1A1200),
    secondaryContainer = InkSurface3,
    onSecondaryContainer = TextSecondary,

    tertiary          = SemanticSuccess,
    onTertiary        = Color(0xFF032018),
    tertiaryContainer = SemanticSuccessBg,
    onTertiaryContainer = SemanticSuccess,

    background            = InkBase,
    onBackground          = TextPrimary,
    surface               = InkSurface1,
    onSurface             = TextPrimary,
    surfaceVariant        = InkSurface2,
    onSurfaceVariant      = TextSecondary,
    surfaceContainer      = InkSurface2,
    surfaceContainerHigh  = InkSurface3,
    surfaceContainerHighest = InkSurface4,
    surfaceContainerLow   = InkSurface1,
    surfaceContainerLowest = InkBase,

    outline        = InkBorder,
    outlineVariant = InkBorderStrong,

    error          = SemanticError,
    onError        = Color.White,
    errorContainer = SemanticErrorBg,
    onErrorContainer = Color(0xFFFFB4B4)
)

private val LightColorScheme = lightColorScheme(
    primary            = GoldDeep,
    onPrimary          = Color.White,
    primaryContainer   = GoldPale,
    onPrimaryContainer = GoldDeep,

    secondary          = Color(0xFF8A6E3E),
    onSecondary        = Color.White,
    secondaryContainer = Color(0xFFF0E8D5),
    onSecondaryContainer = GoldDeep,

    tertiary          = Color(0xFF1D6B50),
    onTertiary        = Color.White,
    tertiaryContainer = Color(0xFFD5F5E8),
    onTertiaryContainer = Color(0xFF0A3D2B),

    background        = PaperBase,
    onBackground      = PaperTextPrimary,
    surface           = PaperSurface1,
    onSurface         = PaperTextPrimary,
    surfaceVariant    = PaperSurface2,
    onSurfaceVariant  = PaperTextSecondary,
    surfaceContainer  = PaperSurface2,
    surfaceContainerHigh  = Color(0xFFEBE7E0),
    surfaceContainerHighest = PaperBorder,
    surfaceContainerLow   = PaperSurface1,
    surfaceContainerLowest = PaperSurface1,

    outline        = PaperBorder,
    outlineVariant = PaperBorderStrong,

    error          = SemanticError,
    onError        = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7A1515)
)

@Composable
fun DocScanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Always false — preserve Obsidian Ink identity
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
