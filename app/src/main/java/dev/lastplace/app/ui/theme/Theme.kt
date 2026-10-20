package dev.lastplace.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EEFF),
    onPrimaryContainer = Color(0xFF102F55),
    inversePrimary = Color(0xFF9AC3FF),
    secondary = Color(0xFF526174),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7EDF5),
    onSecondaryContainer = Color(0xFF233347),
    tertiary = Color(0xFF315D7D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDECF7),
    onTertiaryContainer = Color(0xFF173C58),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF17212F),
    surface = Color.White,
    onSurface = Color(0xFF17212F),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF526174),
    surfaceTint = Color(0xFF1565C0),
    inverseSurface = Color(0xFF26313F),
    inverseOnSurface = Color(0xFFEEF3FA),
    outline = Color(0xFF647489),
    outlineVariant = Color(0xFFD8E1EC),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE4E1),
    onErrorContainer = Color(0xFF601410),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDCE3ED),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F5FA),
    surfaceContainer = Color(0xFFEDF1F7),
    surfaceContainerHigh = Color(0xFFE7EDF5),
    surfaceContainerHighest = Color(0xFFE1E8F1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AC3FF),
    onPrimary = Color(0xFF072A52),
    primaryContainer = Color(0xFF203854),
    onPrimaryContainer = Color(0xFFDBE9FF),
    inversePrimary = Color(0xFF1565C0),
    secondary = Color(0xFFB4C0CF),
    onSecondary = Color(0xFF233347),
    secondaryContainer = Color(0xFF303E50),
    onSecondaryContainer = Color(0xFFE7EDF5),
    tertiary = Color(0xFFA6CBE7),
    onTertiary = Color(0xFF173C58),
    tertiaryContainer = Color(0xFF2A4B64),
    onTertiaryContainer = Color(0xFFDDECF7),
    background = Color(0xFF10141B),
    onBackground = Color(0xFFEEF3FA),
    surface = Color(0xFF181F28),
    onSurface = Color(0xFFEEF3FA),
    surfaceVariant = Color(0xFF2B3543),
    onSurfaceVariant = Color(0xFFB4C0CF),
    surfaceTint = Color(0xFF9AC3FF),
    inverseSurface = Color(0xFFE1E8F1),
    inverseOnSurface = Color(0xFF26313F),
    outline = Color(0xFF8D9BAE),
    outlineVariant = Color(0xFF455263),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF68221D),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
    surfaceBright = Color(0xFF354150),
    surfaceDim = Color(0xFF10141B),
    surfaceContainerLowest = Color(0xFF0C1118),
    surfaceContainerLow = Color(0xFF141B24),
    surfaceContainer = Color(0xFF1C2530),
    surfaceContainerHigh = Color(0xFF25303D),
    surfaceContainerHighest = Color(0xFF303C4B),
)

@Immutable
data class ParkingStatusColors(val warningContainer: Color, val onWarningContainer: Color)

private val LightStatusColors = ParkingStatusColors(Color(0xFFFFF3CF), Color(0xFF634400))
private val DarkStatusColors = ParkingStatusColors(Color(0xFF3E3118), Color(0xFFFFE09A))
val LocalParkingStatusColors = staticCompositionLocalOf { LightStatusColors }

object ParkingLayout {
    val contentWidth = 720.dp
    val gutter = 16.dp
    val sectionSpacing = 24.dp
    val spacing = 8.dp
}

private fun textStyle(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

private val ParkingTypography = Typography(
    headlineLarge = textStyle(32, 40, FontWeight.SemiBold),
    headlineMedium = textStyle(28, 36, FontWeight.SemiBold),
    headlineSmall = textStyle(22, 28, FontWeight.Medium),
    titleLarge = textStyle(22, 28, FontWeight.Medium),
    titleMedium = textStyle(16, 24, FontWeight.Medium),
    titleSmall = textStyle(14, 20, FontWeight.Medium),
    bodyLarge = textStyle(16, 24),
    bodyMedium = textStyle(14, 20),
    bodySmall = textStyle(12, 16),
    labelLarge = textStyle(14, 20, FontWeight.Medium),
    labelMedium = textStyle(14, 20, FontWeight.Medium),
    labelSmall = textStyle(12, 16, FontWeight.Medium),
)

@Composable
fun ParkingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalParkingStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ParkingTypography,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp),
                extraLarge = RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}
