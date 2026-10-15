package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════════════
// MS SCANNER — CALM NORDIC SLATE, SOFT SAGE-EMERALD & WARM SAND PALETTE
// Philosophy: Eye-friendly, low-glare surfaces with soothing botanical
// sage-emerald and warm champagne-sand accents for long work sessions.
// ═══════════════════════════════════════════════════════════════════════

// ── CALM SLATE SURFACES (Dark Palette) ──
val InkBase         = Color(0xFF0F1419)   // Deep calm slate — restful main background
val InkSurface1     = Color(0xFF161D24)   // Elevated card / list surface
val InkSurface2     = Color(0xFF1D262F)   // Bottom sheets, dialogs, glass cards
val InkSurface3     = Color(0xFF25303B)   // Chips, pills, input fields
val InkSurface4     = Color(0xFF2E3B48)   // Active/hover states, selected items
val InkBorder       = Color(0xFF2A3642)   // Soft hairline borders
val InkBorderStrong = Color(0xFF394858)   // Emphasized card borders

// ── WARM CHAMPAGNE-SAND ACCENT RAMP ──
val GoldDeep      = Color(0xFF7D6336)     // Warm bronze on light surfaces
val GoldBase      = Color(0xFFD4B785)     // Soft champagne sand — calm & refined
val GoldLight     = Color(0xFFE5CE9E)     // Lighter sand highlight
val GoldPale      = Color(0xFFF4EAD5)     // Soft warm tint for light mode containers
val GoldOnSurface = Color(0xFFD4B785)

// ── SOFT SAGE-EMERALD & SKY TEAL ACCENTS ──
val Emerald400            = Color(0xFF4DB690) // Calm botanical sage-emerald (primary scan & action accent)
val EmeraldLight          = Color(0xFF76CCA9) // Soft mint-sage highlight
val EmeraldPrimaryLight   = Color(0xFF2B7A5E) // Deep sage for light mode
val EmeraldPrimaryDark    = Color(0xFF4DB690)
val EmeraldContainerLight = Color(0xFFDDF3EA)
val EmeraldContainerDark  = Color(0xFF132E25)
val OnEmeraldContainerLight = Color(0xFF0F3829)
val OnEmeraldContainerDark  = Color(0xFF88D9B8)
val CyanScan              = Color(0xFF5BA4CF) // Calm sky-teal for AI & OCR accents
val CyanScanLight         = Color(0xFF86C0E2)
val GoldStar              = GoldBase

// ── TEXT RAMP (Dark Mode — Soft Alabaster, never harsh pure white) ──
val TextPrimary   = Color(0xFFECEFF4)     // Soft Nordic snow-white
val TextSecondary = Color(0xFF9BA8B8)     // Calm muted slate-blue for subtitles
val TextTertiary  = Color(0xFF687686)     // Gentle captions & timestamps
val TextDisabled  = Color(0xFF434E5B)     // Disabled labels

// ── LIGHT PALETTE (Warm Alabaster Paper — zero eye strain) ──
val PaperBase          = Color(0xFFF5F4EE) // Warm matte paper background
val PaperSurface1      = Color(0xFFFCFBF8) // Soft ivory card surface
val PaperSurface2      = Color(0xFFECEAE2) // Subtle section container
val PaperBorder        = Color(0xFFDFDDD3) // Gentle hairlines
val PaperBorderStrong  = Color(0xFFC8C5B8) // Dividers
val PaperTextPrimary   = Color(0xFF1A2129) // Deep charcoal-slate ink
val PaperTextSecondary = Color(0xFF5C6773) // Supporting text
val PaperTextTertiary  = Color(0xFF8C96A2) // Captions

// ── SEMANTIC COLORS (Soothing & Balanced) ──
val SemanticSuccess   = Color(0xFF4DB690) // Soft sage-emerald
val SemanticSuccessBg = Color(0xFF122C23)
val SemanticWarning   = Color(0xFFE2AE52) // Warm honey amber
val SemanticWarningBg = Color(0xFF2B2110)
val SemanticError     = Color(0xFFD96B6B) // Soft coral red (non-jarring)
val SemanticErrorBg   = Color(0xFF2C1416)
val SemanticInfo      = Color(0xFF5BA4CF) // Calm sky blue

// ── CAMERA & STUDIO VIEWFINDER ──
val ViewfinderBg       = Color(0xFF0B0F14) // Deep studio slate canvas
val ViewfinderOverlay  = Color(0xCC121820) // Frosted glass overlay strips
val ScanFrameGold      = GoldBase
val ScanFrameDetected  = Emerald400

val StudioCanvasBg       = ViewfinderBg
val StudioCardOverlay    = ViewfinderOverlay
val WarningAmber         = SemanticWarning
val WarningContainerDark = SemanticWarningBg
val ErrorRed             = SemanticError
val ErrorContainerLight  = Color(0xFFFCE8E8)
val ErrorContainerDark   = SemanticErrorBg
val SuccessGreen         = SemanticSuccess
