package dev.titanide.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * TITAN's palette. Values are the documented brand starting points; semantic names
 * (surface/canvas/outline) are what components should use so the theme can be retuned
 * without touching UI code.
 */
object TitanColors {
    val Canvas = Color(0xFF090B10)
    val CanvasElevated = Color(0xFF10141D)
    val Surface = Color(0xFF171D28)
    val SurfaceHigh = Color(0xFF1F2735)
    val SurfaceHigher = Color(0xFF28313F)

    val Primary = Color(0xFF795CFF)
    val PrimaryContainer = Color(0xFF2A2350)
    val Secondary = Color(0xFF00D4FF)
    val SecondaryContainer = Color(0xFF062A33)

    val Success = Color(0xFF36D399)
    val Warning = Color(0xFFFFCA65)
    val Error = Color(0xFFFF647C)
    val Info = Color(0xFF64B5FF)

    val TextPrimary = Color(0xFFF4F6FC)
    val TextMuted = Color(0xFF8993A7)
    val Outline = Color(0xFF2C3543)
    val OutlineFaint = Color(0xFF1E2634)

    val GitAdded = Color(0xFF2EA043)
    val GitModified = Color(0xFFD29922)
    val GitDeleted = Color(0xFFF85149)
}

/** Syntax palette shared by the editor and any read-only code surface. */
object SyntaxColors {
    val Keyword = Color(0xFFC792EA)
    val String = Color(0xFF9ECE6A)
    val Number = Color(0xFFFF9E64)
    val Comment = Color(0xFF5C6773)
    val Function = Color(0xFF7AA2F7)
    val Type = Color(0xFF2AC3DE)
    val Annotation = Color(0xFFFFCA65)
    val Operator = Color(0xFF89DDFF)
    val Constant = Color(0xFFFF9E64)
    val Plain = Color(0xFFF4F6FC)
    val Punctuation = Color(0xFFA9B1D6)
    val Property = Color(0xFF73DACA)
    val Tag = Color(0xFFF7768E)
}
