package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════
// MS SCANNER — OBSIDIAN INK DESIGN SYSTEM
// Philosophy: One gold accent. Deep ink surfaces. No noise.
// ═══════════════════════════════════════════════════════

// ── INK SURFACES (Dark Palette) ──
// True obsidian — near-black with a subtle warm-indigo tint.
// Avoids the cold blue-gray common in generic dark themes.
val InkBase      = Color(0xFF0A090E)   // Page canvas — deepest layer
val InkSurface1  = Color(0xFF111018)   // Elevated card / list item
val InkSurface2  = Color(0xFF1A1825)   // Bottom sheets, dialogs
val InkSurface3  = Color(0xFF242235)   // Chips, pills, input fields
val InkSurface4  = Color(0xFF2E2C42)   // Active/hover states, selected items
val InkBorder    = Color(0xFF2A2840)   // Default hairline borders
val InkBorderStrong = Color(0xFF3D3B56) // Emphasized borders

// ── GOLD ACCENT RAMP ──
// Warm archival gold — NOT yellow. Evokes stamped letterhead, notary seals.
// Used exclusively for: primary actions, active states, scan corners, FAB.
val GoldDeep     = Color(0xFF8A6E3E)   // Darkest — on-light-background text
val GoldBase     = Color(0xFFC8A96E)   // Core brand accent
val GoldLight    = Color(0xFFE8C98A)   // Hover / lighter states
val GoldPale     = Color(0xFFF5E6C0)   // Tint backgrounds (use at max 15% opacity)
val GoldOnSurface = Color(0xFFC8A96E)  // Gold on dark surfaces (same as GoldBase)

// ── TEXT RAMP ──
val TextPrimary   = Color(0xFFF0EFF8)  // Near-white with a whisper of violet
val TextSecondary = Color(0xFF9896B0)  // Muted — subtitles, metadata
val TextTertiary  = Color(0xFF5E5C76)  // Ghost — timestamps, placeholders
val TextDisabled  = Color(0xFF3A3850)  // Disabled labels

// ── LIGHT PALETTE ──
// Clean paper with a very slight cream warmth — not stark white.
val PaperBase     = Color(0xFFF7F5F0)  // Page background — warm white
val PaperSurface1 = Color(0xFFFFFFFF)  // Cards
val PaperSurface2 = Color(0xFFF2F0EB)  // Subtle section backgrounds
val PaperBorder   = Color(0xFFE4E0D8)  // Hairlines
val PaperBorderStrong = Color(0xFFCDC9BE) // Dividers
val PaperTextPrimary   = Color(0xFF18161F) // Near-black ink
val PaperTextSecondary = Color(0xFF6B6880) // Supporting text
val PaperTextTertiary  = Color(0xFF9E9BB0) // Captions

// ── SEMANTIC COLORS ──
val SemanticSuccess  = Color(0xFF3EE0A0)  // Scan detected / quality good
val SemanticSuccessBg = Color(0xFF0D2E22) // Dark mode success tint
val SemanticWarning  = Color(0xFFEDB84A)  // Blur warning / low light
val SemanticWarningBg = Color(0xFF2E1F08)
val SemanticError    = Color(0xFFE05555)  // Delete / error states
val SemanticErrorBg  = Color(0xFF2E0D0D)
val SemanticInfo     = Color(0xFF5B9CF6)  // OCR running / info

// ── CAMERA VIEWFINDER ──
val ViewfinderBg      = Color(0xFF05040A)  // Deeper than InkBase for immersion
val ViewfinderOverlay = Color(0xCC07060E)  // Semi-transparent control strips
val ScanFrameGold     = Color(0xFFC8A96E)  // Corner detection frame = GoldBase
val ScanFrameDetected = Color(0xFF3EE0A0)  // Green corners when document found

// ── BACKWARD COMPATIBILITY ──
val EmeraldPrimaryLight = GoldBase          // was Emerald700
val EmeraldPrimaryDark  = GoldLight         // was Emerald400
val EmeraldLight        = GoldLight
val EmeraldContainerLight = GoldPale
val EmeraldContainerDark  = Color(0xFF1A1200)
val OnEmeraldContainerLight = GoldDeep
val OnEmeraldContainerDark  = GoldLight
val CyanScan      = GoldBase
val CyanScanLight = GoldLight
val GoldStar      = GoldBase               // Favorite star = same gold

val StudioCanvasBg  = ViewfinderBg
val StudioCardOverlay = ViewfinderOverlay
val WarningAmber      = SemanticWarning
val WarningContainerDark = SemanticWarningBg
val ErrorRed = SemanticError
val ErrorContainerLight = Color(0xFFFEE2E2)
val ErrorContainerDark  = SemanticErrorBg
val SuccessGreen = SemanticSuccess

// BACKWARD COMPATIBILITY ADDITION
val Emerald400 = GoldBase
