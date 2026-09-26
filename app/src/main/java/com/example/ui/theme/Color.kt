package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// MS SCANNER - MODERN EXECUTIVE DESIGN SYSTEM PALETTE
// Inspired by world-class productivity tools: Adobe Scan, Apple Notes, Scanner Pro
// ============================================================================

// --- Brand Primary (Precision Emerald / Deep Alpine) ---
// Commanding, trusted, razor-sharp green tone
val Emerald900 = Color(0xFF064E3B)
val Emerald800 = Color(0xFF065F46)
val Emerald700 = Color(0xFF047857)
val Emerald600 = Color(0xFF059669)
val Emerald500 = Color(0xFF10B981)
val Emerald400 = Color(0xFF34D399)
val Emerald300 = Color(0xFF6EE7B7)
val Emerald200 = Color(0xFFA7F3D0)
val Emerald100 = Color(0xFFD1FAE5)
val Emerald50 = Color(0xFFECFDF5)

// --- Brand Secondary (Precision Laser Cyan / Tech Ray) ---
val Cyan900 = Color(0xFF0C4A6E)
val Cyan800 = Color(0xFF075985)
val Cyan700 = Color(0xFF0369A1)
val Cyan600 = Color(0xFF0284C7)
val Cyan500 = Color(0xFF0EA5E9)
val Cyan400 = Color(0xFF38BDF8)
val Cyan300 = Color(0xFF7DD3FC)
val Cyan100 = Color(0xFFE0F2FE)
val Cyan50 = Color(0xFFF0F9FF)

// --- Light Palette Tokens (Pure Alabaster & Crisp Executive Paper) ---
val LightBg = Color(0xFFF8FAFC)                 // Clean ultra-subtle off-white
val LightSurface = Color(0xFFFFFFFF)            // Pure white cards & dialogs
val LightSurfaceContainerLow = Color(0xFFF8FAFC)
val LightSurfaceContainer = Color(0xFFF1F5F9)   // Subtle section contrast
val LightSurfaceContainerHigh = Color(0xFFE2E8F0)
val LightSurfaceContainerHighest = Color(0xFFCBD5E1)
val LightOutline = Color(0xFFE2E8F0)            // Micro borders
val LightOutlineVariant = Color(0xFFF1F5F9)     // Soft dividers
val LightTextPrimary = Color(0xFF0F172A)        // Deep slate ink typography
val LightTextSecondary = Color(0xFF475569)      // Subtitles & metadata
val LightTextTertiary = Color(0xFF94A3B8)       // Timestamps & hints

// --- Dark Palette Tokens (Studio Obsidian & Midnight Slate) ---
// Deep, battery-saving, true dark without murky gray tones
val DarkBg = Color(0xFF090D14)                  // Deepest midnight obsidian
val DarkSurface = Color(0xFF111827)             // Elevated card surface
val DarkSurfaceContainerLow = Color(0xFF0E1422)
val DarkSurfaceContainer = Color(0xFF161F30)    // Secondary container
val DarkSurfaceContainerHigh = Color(0xFF1E293B)// Higher elevation pill/card
val DarkSurfaceContainerHighest = Color(0xFF283548)
val DarkOutline = Color(0xFF334155)             // Structural separation
val DarkOutlineVariant = Color(0xFF1E293B)      // Dividers
val DarkTextPrimary = Color(0xFFF8FAFC)         // Crisp high contrast text
val DarkTextSecondary = Color(0xFF94A3B8)       // Secondary readable text
val DarkTextTertiary = Color(0xFF64748B)        // Muted captions

// --- Functional & Semantic Highlights ---
val WarningAmber = Color(0xFFF59E0B)
val WarningContainerLight = Color(0xFFFEF3C7)
val WarningContainerDark = Color(0xFF451A03)
val ErrorRed = Color(0xFFEF4444)
val ErrorContainerLight = Color(0xFFFEE2E2)
val ErrorContainerDark = Color(0xFF450A0A)
val SuccessGreen = Color(0xFF10B981)
val GoldStar = Color(0xFFFBBF24)

// --- Studio Lightbox (Document Viewer / Camera) ---
val StudioCanvasBg = Color(0xFF080C14)
val StudioCardOverlay = Color(0xCC0B111E)
val GlassmorphismBgLight = Color(0xEBFFFFFF)
val GlassmorphismBgDark = Color(0xE0111827)

// Backward compatibility references for existing code
val CyanScan = Cyan600
val CyanScanLight = Cyan400
val EmeraldPrimaryLight = Emerald700
val EmeraldPrimaryDark = Emerald400
val EmeraldLight = Emerald400
val EmeraldContainerLight = Emerald50
val EmeraldContainerDark = Emerald900
val OnEmeraldContainerLight = Emerald800
val OnEmeraldContainerDark = Emerald200
