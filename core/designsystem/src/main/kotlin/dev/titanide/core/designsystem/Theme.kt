package dev.titanide.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TitanColorScheme = darkColorScheme(
    primary = TitanColors.Primary,
    onPrimary = TitanColors.TextPrimary,
    primaryContainer = TitanColors.PrimaryContainer,
    onPrimaryContainer = TitanColors.TextPrimary,
    secondary = TitanColors.Secondary,
    onSecondary = TitanColors.Canvas,
    secondaryContainer = TitanColors.SecondaryContainer,
    onSecondaryContainer = TitanColors.Secondary,
    tertiary = TitanColors.Success,
    background = TitanColors.Canvas,
    onBackground = TitanColors.TextPrimary,
    surface = TitanColors.Surface,
    onSurface = TitanColors.TextPrimary,
    surfaceVariant = TitanColors.SurfaceHigh,
    onSurfaceVariant = TitanColors.TextMuted,
    surfaceContainer = TitanColors.CanvasElevated,
    surfaceContainerHigh = TitanColors.Surface,
    surfaceContainerHighest = TitanColors.SurfaceHigh,
    outline = TitanColors.Outline,
    outlineVariant = TitanColors.OutlineFaint,
    error = TitanColors.Error,
    onError = TitanColors.Canvas,
)

private val TitanTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 56.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
)

private val TitanShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Editor metrics kept outside Material so the code surface can diverge. */
data class TitanDimens(
    val codeFontSize: Int = 13,
    val codeLineHeight: Int = 20,
    val gutterWidth: Int = 44,
    val gutterPadding: Int = 6,
    val tabWidth: Int = 4,
    val contentPadding: Int = 12,
) {
    val codeFontFamily: FontFamily = FontFamily.Monospace
}

val LocalTitanDimens = staticCompositionLocalOf { TitanDimens() }

@Composable
fun TitanTheme(
    dimens: TitanDimens = TitanDimens(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTitanDimens provides dimens) {
        MaterialTheme(
            colorScheme = TitanColorScheme,
            typography = TitanTypography,
            shapes = TitanShapes,
            content = content,
        )
    }
}
