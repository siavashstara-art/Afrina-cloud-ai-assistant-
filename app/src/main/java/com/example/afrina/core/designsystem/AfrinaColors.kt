package com.example.afrina.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Centralized AFRINA Warm Luxury Creative Studio Color Tokens.
 *
 * Light theme centers on Warm Ivory (#F4EFE6), Soft Cream (#EAE2D6),
 * Terracotta Copper (#A65E43), Champagne Gold (#D6B58A), Muted Sage (#78816B),
 * Warm Charcoal (#302C28), and Warm Taupe (#81766B).
 * Dark theme mirrors the palette in Warm Charcoal, Deep Brown, Muted Copper, and Champagne Gold.
 */
object AfrinaPalette {
    // Warm Luxury Creative Studio — Light Surface Stack (Default)
    val WarmIvoryBg = Color(0xFFF4EFE6)
    val ElevatedIvorySurface = Color(0xFFFAF6EE)
    val SoftCreamSurface = Color(0xFFEAE2D6)
    val WarmLinenOutline = Color(0xFFD5CBBF)

    // Warm Atelier — Dark Surface Stack (No navy, no electric blue)
    val WarmCharcoalBg = Color(0xFF1A1613)
    val DeepBrownSurface = Color(0xFF241E1A)
    val EspressoElevatedSurface = Color(0xFF302823)
    val DarkTaupeOutline = Color(0xFF493E36)

    // Brand Signature Accents
    val TerracottaCopper = Color(0xFFA65E43)
    val TerracottaContainerLight = Color(0xFFF3DDD3)
    val MutedCopperDark = Color(0xFFC97E61)
    val MutedCopperContainerDark = Color(0xFF4C2B1F)

    val ChampagneGold = Color(0xFFD6B58A)
    val ChampagneContainerLight = Color(0xFFF2E6D5)
    val ChampagneContainerDark = Color(0xFF473A29)

    val MutedSage = Color(0xFF78816B)
    val SageContainerLight = Color(0xFFE4E8DC)
    val MutedSageDark = Color(0xFF9AA58B)
    val SageContainerDark = Color(0xFF2F3627)

    // Semantic Studio Status Tones (accessible warm tones, zero cold/neon hues)
    val SageHealthy = Color(0xFF5D6B4F)
    val OchreWarning = Color(0xFFB47835)
    val RustAlert = Color(0xFFAD4537)
    val BronzeSimulated = Color(0xFF967146)

    // Typography Ink Hierarchy
    val WarmCharcoalInk = Color(0xFF302C28)
    val WarmTaupe = Color(0xFF81766B)
    val WarmTaupeAccessible = Color(0xFF685E54)

    val IvoryInkDark = Color(0xFFF4EFE6)
    val CreamSecondaryDark = Color(0xFFC8BEB1)
}
