package com.vehiclescanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vehiclescanner.core.PlateStatus
import com.vehiclescanner.data.ThemeMode

private val Navy = Color(0xFF0F2A4A)
private val Blue = Color(0xFF2563EB)

private val LightColors = lightColorScheme(
    primary = Navy, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6F5), onPrimaryContainer = Navy,
    secondary = Blue, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3ECFD), onSecondaryContainer = Color(0xFF0B2A6B),
    background = Color(0xFFF6F7F9), onBackground = Color(0xFF111827),
    surface = Color.White, onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF1F5), onSurfaceVariant = Color(0xFF4B5563),
    surfaceContainer = Color(0xFFF1F3F6), surfaceContainerHigh = Color(0xFFEAEDF1),
    outline = Color(0xFFD1D5DB), outlineVariant = Color(0xFFE5E7EB),
    error = Color(0xFFDC2626)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC2FF), onPrimary = Color(0xFF0A1B33),
    primaryContainer = Color(0xFF1C3557), onPrimaryContainer = Color(0xFFD7E5FF),
    secondary = Color(0xFF7FA8FF), onSecondary = Color(0xFF0A1B33),
    secondaryContainer = Color(0xFF1E2C47), onSecondaryContainer = Color(0xFFD7E5FF),
    background = Color(0xFF0E1116), onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF161A21), onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1F2530), onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceContainer = Color(0xFF1A1F27), surfaceContainerHigh = Color(0xFF222833),
    outline = Color(0xFF374151), outlineVariant = Color(0xFF2A303B),
    error = Color(0xFFF87171)
)

/** Semantic status colours (green = match, red = not found, amber = review). */
@Immutable
data class StatusColors(
    val match: Color, val matchBg: Color,
    val notFound: Color, val notFoundBg: Color,
    val review: Color, val reviewBg: Color
) {
    fun fg(s: PlateStatus) = when (s) { PlateStatus.MATCH -> match; PlateStatus.NOT_FOUND -> notFound; PlateStatus.REVIEW -> review }
    fun bg(s: PlateStatus) = when (s) { PlateStatus.MATCH -> matchBg; PlateStatus.NOT_FOUND -> notFoundBg; PlateStatus.REVIEW -> reviewBg }
}

private val LightStatus = StatusColors(
    Color(0xFF15803D), Color(0xFFDCFCE7), Color(0xFFB91C1C), Color(0xFFFEE2E2), Color(0xFFB45309), Color(0xFFFEF3C7)
)
private val DarkStatus = StatusColors(
    Color(0xFF4ADE80), Color(0xFF12301F), Color(0xFFF87171), Color(0xFF3A1717), Color(0xFFFBBF24), Color(0xFF3A2C0F)
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Monospace style for registration numbers – easier to compare character by character. */
val PlateTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 1.sp)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
    )
}

@Composable
fun VehicleScannerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
    }
}
