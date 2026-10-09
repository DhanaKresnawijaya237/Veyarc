package com.veyarc.app.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

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
        background = Color(0xFF111014),
        onBackground = Color(0xFFE8E0EB),
        surface = Color(0xFF111014),
        onSurface = Color(0xFFE8E0EB),
        surfaceDim = Color(0xFF111014),
        surfaceBright = Color(0xFF37353D),
        surfaceContainerLowest = Color(0xFF0D0C10),
        surfaceContainerLow = Color(0xFF19181D),
        surfaceContainer = Color(0xFF1F1E24),
        surfaceContainerHigh = Color(0xFF27262D),
        surfaceContainerHighest = Color(0xFF323038),
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
        background = Color(0xFFFAF9FC),
        onBackground = Color(0xFF1E1A20),
        surface = Color(0xFFFAF9FC),
        onSurface = Color(0xFF1E1A20),
        surfaceDim = Color(0xFFDCDCE2),
        surfaceBright = Color(0xFFFAF9FC),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF4F3F7),
        surfaceContainer = Color(0xFFEEEEF2),
        surfaceContainerHigh = Color(0xFFE8E7ED),
        surfaceContainerHighest = Color(0xFFE2E1E8),
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
        headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
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
    val view = LocalView.current
    val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    if (!view.isInEditMode && activity != null) {
        SideEffect {
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    // Use shared surface colors so sheets and menus keep the same tint at every elevation.
    CompositionLocalProvider(LocalTonalElevationEnabled provides false) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = VeyarcTypography,
            shapes = VeyarcShapes,
            content = content,
        )
    }
}
