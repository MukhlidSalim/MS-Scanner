package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val DarkColorScheme = darkColorScheme(
    primary                = Emerald400,
    onPrimary              = Color(0xFF072117),
    primaryContainer       = EmeraldContainerDark,
    onPrimaryContainer     = EmeraldLight,

    secondary              = GoldBase,
    onSecondary            = Color(0xFF1F1709),
    secondaryContainer     = InkSurface3,
    onSecondaryContainer   = GoldLight,

    tertiary               = CyanScan,
    onTertiary             = Color(0xFF071E2C),
    tertiaryContainer      = Color(0xFF132A38),
    onTertiaryContainer    = CyanScanLight,

    background             = InkBase,
    onBackground           = TextPrimary,
    surface                = InkSurface1,
    onSurface              = TextPrimary,
    surfaceVariant         = InkSurface2,
    onSurfaceVariant       = TextSecondary,
    surfaceContainer       = InkSurface2,
    surfaceContainerHigh   = InkSurface3,
    surfaceContainerHighest = InkSurface4,
    surfaceContainerLow    = InkSurface1,
    surfaceContainerLowest = InkBase,

    outline                = InkBorder,
    outlineVariant         = InkBorderStrong,

    error                  = SemanticError,
    onError                = Color.White,
    errorContainer         = SemanticErrorBg,
    onErrorContainer       = Color(0xFFFFB8B8)
)

private val LightColorScheme = lightColorScheme(
    primary                = EmeraldPrimaryLight,
    onPrimary              = Color.White,
    primaryContainer       = EmeraldContainerLight,
    onPrimaryContainer     = OnEmeraldContainerLight,

    secondary              = GoldDeep,
    onSecondary            = Color.White,
    secondaryContainer     = GoldPale,
    onSecondaryContainer   = GoldDeep,

    tertiary               = Color(0xFF2B6CB0),
    onTertiary             = Color.White,
    tertiaryContainer      = Color(0xFFDCEEFB),
    onTertiaryContainer    = Color(0xFF103454),

    background             = PaperBase,
    onBackground           = PaperTextPrimary,
    surface                = PaperSurface1,
    onSurface              = PaperTextPrimary,
    surfaceVariant         = PaperSurface2,
    onSurfaceVariant       = PaperTextSecondary,
    surfaceContainer       = PaperSurface2,
    surfaceContainerHigh   = Color(0xFFE6E4DA),
    surfaceContainerHighest = PaperBorder,
    surfaceContainerLow    = PaperSurface1,
    surfaceContainerLowest = PaperSurface1,

    outline                = PaperBorder,
    outlineVariant         = PaperBorderStrong,

    error                  = SemanticError,
    onError                = Color.White,
    errorContainer         = ErrorContainerLight,
    onErrorContainer       = Color(0xFF7A1515)
)

@Composable
fun DocScanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
