package com.skiephone.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Skiephone design tokens — derived from the official Skiephone Brand Asset Book.
 * Single source of truth for color across the app. Light theme throughout,
 * matching the brand book's print/document palette exactly.
 *
 * Usage ratio per brand book: ~60% white space, 25% Sky Blue, 10% Navy, 5% Coral.
 * Coral is reserved for small accents only — ticks, badges, highlight bars —
 * never large fills.
 */
object SkieColors {
    val SkyBlue   = Color(0xFF3AA0E8)   // Primary — headers, fills, key actions
    val DeepNavy  = Color(0xFF103A5C)   // Headings, dark text
    val Coral     = Color(0xFFFF7A59)   // Accent only — sparing use
    val SkyTint   = Color(0xFFEAF4FC)   // Soft backgrounds, grouped sections
    val Ink       = Color(0xFF1F2937)   // Body text
    val Grey      = Color(0xFF6B7280)   // Captions, secondary text
    val Line      = Color(0xFFD7E3EE)   // Dividers, table lines
    val White     = Color(0xFFFFFFFF)

    // Semantic states — kept distinct from brand accent colors so "danger"
    // and "success" are never confused with the brand's warm accent.
    val Success   = Color(0xFF34A853)
    val SuccessBg = Color(0xFFE8F5E9)
    val Danger    = Color(0xFFE0432B)
    val DangerBg  = Color(0xFFFBE9E7)
    val Warning   = Color(0xFFF59E0B)
    val WarningBg = Color(0xFFFEF3E0)
}

/** Standard corner radius scale — brand book specifies ~3mm (≈12dp) on bands/boxes. */
object SkieRadius {
    val card = 16.dp
    val button = 14.dp
    val chip = 10.dp
    val sheet = 24.dp
}

/** Standard spacing scale for consistent rhythm across all screens. */
object SkieSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}