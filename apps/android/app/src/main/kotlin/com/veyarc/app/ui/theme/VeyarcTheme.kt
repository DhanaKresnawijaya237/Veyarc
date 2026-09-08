package com.veyarc.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFD5B9FF),
        onPrimary = Color(0xFF3D006E),
        primaryContainer = Color(0xFF57218A),
        onPrimaryContainer = Color(0xFFF0DCFF),
        secondary = Color(0xFFC1C8FF),
        onSecondary = Color(0xFF242D61),
        secondaryContainer = Color(0xFF3B4479),
        onSecondaryContainer = Color(0xFFDEE0FF),
        background = Color(0xFF100E14),
        onBackground = Color(0xFFE8E0EB),
        surface = Color(0xFF100E14),
        onSurface = Color(0xFFE8E0EB),
        surfaceVariant = Color(0xFF4A454D),
        onSurfaceVariant = Color(0xFFCDC4CE),
        outline = Color(0xFF968E98),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
    )

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF713AA7),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFF0DCFF),
        onPrimaryContainer = Color(0xFF29004E),
        secondary = Color(0xFF535C91),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDEE0FF),
        onSecondaryContainer = Color(0xFF101A4A),
        background = Color(0xFFFFF7FF),
        onBackground = Color(0xFF1E1A20),
        surface = Color(0xFFFFF7FF),
        onSurface = Color(0xFF1E1A20),
        surfaceVariant = Color(0xFFEAE0EA),
        onSurfaceVariant = Color(0xFF4A454D),
        outline = Color(0xFF7B747D),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
    )

private val BaseTypography = Typography()

private val VeyarcTypography =
    Typography(
        headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )

private val VeyarcShapes =
    Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(28.dp),
    )

@Composable
fun VeyarcTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = VeyarcTypography,
        shapes = VeyarcShapes,
        content = content,
    )
}
