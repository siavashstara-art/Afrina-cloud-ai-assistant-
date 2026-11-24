package com.example.afrina.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.afrina.core.i18n.LocalAfrinaStrings
import com.example.afrina.core.i18n.LocalAppLanguage
import com.example.afrina.core.i18n.stringsFor
import com.example.afrina.core.model.AppLanguage
import com.example.afrina.core.model.ThemePreference

private val AfrinaDarkColorScheme = darkColorScheme(
    primary = AfrinaPalette.ElectricCyan,
    onPrimary = Color(0xFF041421),
    primaryContainer = Color(0xFF0C2E45),
    onPrimaryContainer = Color(0xFFB8F3FF),
    secondary = AfrinaPalette.CobaltBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF17284B),
    onSecondaryContainer = Color(0xFFD6E4FF),
    tertiary = AfrinaPalette.VioletSimulated,
    onTertiary = Color(0xFF1B1035),
    background = AfrinaPalette.ObsidianBg,
    onBackground = AfrinaPalette.TextPrimaryDark,
    surface = AfrinaPalette.MidnightSurface,
    onSurface = AfrinaPalette.TextPrimaryDark,
    surfaceVariant = AfrinaPalette.ElevatedSlate,
    onSurfaceVariant = AfrinaPalette.TextSecondaryDark,
    outline = AfrinaPalette.SlateOutline,
    error = AfrinaPalette.RoseAlert,
    onError = Color.White,
)

private val AfrinaLightColorScheme = lightColorScheme(
    primary = AfrinaPalette.DeepCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F3FF),
    onPrimaryContainer = Color(0xFF06293D),
    secondary = AfrinaPalette.CobaltBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0ECFF),
    onSecondaryContainer = Color(0xFF0F264A),
    tertiary = AfrinaPalette.IndigoAccent,
    onTertiary = Color.White,
    background = AfrinaPalette.IceBg,
    onBackground = AfrinaPalette.TextPrimaryLight,
    surface = AfrinaPalette.PaperSurface,
    onSurface = AfrinaPalette.TextPrimaryLight,
    surfaceVariant = AfrinaPalette.SoftSlateSurface,
    onSurfaceVariant = AfrinaPalette.TextSecondaryLight,
    outline = AfrinaPalette.LightOutline,
    error = AfrinaPalette.RoseAlert,
    onError = Color.White,
)

private val AfrinaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun AfrinaTheme(
    language: AppLanguage = AppLanguage.PERSIAN,
    themePreference: ThemePreference = ThemePreference.DARK,
    content: @Composable () -> Unit,
) {
    val isDark = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }
    val colorScheme = if (isDark) AfrinaDarkColorScheme else AfrinaLightColorScheme
    val layoutDirection = if (language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val strings = stringsFor(language)

    CompositionLocalProvider(
        LocalLayoutDirection provides layoutDirection,
        LocalAfrinaStrings provides strings,
        LocalAppLanguage provides language,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AfrinaTypography,
            shapes = AfrinaShapes,
            content = content,
        )
    }
}
