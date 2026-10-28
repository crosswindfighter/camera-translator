package com.example.cameratranslator.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ayarlar ve Hakkında ekranlarındaki bölüm kartlarının ortak görünümü.
 *
 * Açık temada dinamik renkler (duvar kağıdından gelen) kartı arka plana çok yakın
 * yapıyordu; bu yüzden açık temada "gri zemin + beyaz kart + kenarlık + hafif gölge"
 * kullanılır. Koyu tema eskisi gibi kalır.
 */
object SectionCardStyle {

    @Composable
    fun isLight(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

    /** Ekran (Scaffold ve üst çubuk) arka planı. */
    @Composable
    fun screenBackground(): Color =
        if (isLight()) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface

    /** Kart rengi; [darkAlpha] koyu temadaki eski şeffaflık. */
    @Composable
    fun cardColor(darkAlpha: Float): Color =
        if (isLight()) MaterialTheme.colorScheme.surfaceContainerLowest
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = darkAlpha)

    @Composable
    fun cardBorder(showInDark: Boolean = true): BorderStroke? = when {
        isLight() -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        showInDark -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        else -> null
    }

    @Composable
    fun cardShadow(): Dp = if (isLight()) 2.dp else 0.dp

    /** Kart içindeki seçim şeritlerinin (segmented) zemini: beyaz kart üstünde de seçilsin. */
    @Composable
    fun segmentBackground(): Color =
        if (isLight()) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
}
