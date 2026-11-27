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

private val AfrinaLightColorScheme = lightColorScheme(
    primary = AfrinaPalette.TerracottaCopper,
    onPrimary = AfrinaPalette.ElevatedIvorySurface,
    primaryContainer = AfrinaPalette.TerracottaContainerLight,
    onPrimaryContainer = Color(0xFF4A2416),
    secondary = AfrinaPalette.ChampagneGold,
    onSecondary = AfrinaPalette.WarmCharcoalInk,
    secondaryContainer = AfrinaPalette.ChampagneContainerLight,
    onSecondaryContainer = AfrinaPalette.WarmCharcoalInk,
    tertiary = AfrinaPalette.MutedSage,
    onTertiary = AfrinaPalette.ElevatedIvorySurface,
    tertiaryContainer = AfrinaPalette.SageContainerLight,
    onTertiaryContainer = Color(0xFF283020),
    background = AfrinaPalette.WarmIvoryBg,
    onBackground = AfrinaPalette.WarmCharcoalInk,
    surface = AfrinaPalette.ElevatedIvorySurface,
    onSurface = AfrinaPalette.WarmCharcoalInk,
    surfaceVariant = AfrinaPalette.SoftCreamSurface,
    onSurfaceVariant = AfrinaPalette.WarmTaupeAccessible,
    outline = AfrinaPalette.WarmLinenOutline,
    outlineVariant = AfrinaPalette.SoftCreamSurface,
    error = AfrinaPalette.RustAlert,
    onError = AfrinaPalette.ElevatedIvorySurface,
)

private val AfrinaDarkColorScheme = darkColorScheme(
    primary = AfrinaPalette.MutedCopperDark,
    onPrimary = AfrinaPalette.WarmCharcoalBg,
    primaryContainer = AfrinaPalette.MutedCopperContainerDark,
    onPrimaryContainer = Color(0xFFF6DCD0),
    secondary = AfrinaPalette.ChampagneGold,
    onSecondary = AfrinaPalette.WarmCharcoalBg,
    secondaryContainer = AfrinaPalette.ChampagneContainerDark,
    onSecondaryContainer = Color(0xFFF2E5D4),
    tertiary = AfrinaPalette.MutedSageDark,
    onTertiary = AfrinaPalette.WarmCharcoalBg,
    tertiaryContainer = AfrinaPalette.SageContainerDark,
    onTertiaryContainer = Color(0xFFDCE3D3),
    background = AfrinaPalette.WarmCharcoalBg,
    onBackground = AfrinaPalette.IvoryInkDark,
    surface = AfrinaPalette.DeepBrownSurface,
    onSurface = AfrinaPalette.IvoryInkDark,
    surfaceVariant = AfrinaPalette.EspressoElevatedSurface,
    onSurfaceVariant = AfrinaPalette.CreamSecondaryDark,
    outline = AfrinaPalette.DarkTaupeOutline,
    outlineVariant = AfrinaPalette.EspressoElevatedSurface,
    error = Color(0xFFE07365),
    onError = AfrinaPalette.WarmCharcoalBg,
)

private val AfrinaShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AfrinaTheme(
    language: AppLanguage = AppLanguage.PERSIAN,
    themePreference: ThemePreference = ThemePreference.LIGHT,
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
