// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import helium314.keyboard.latin.R

@Composable
fun Theme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val material3 = Typography()
    // OpenWhispr: settings are always black with neutral accents (no wallpaper colors).
    val colorScheme = if (true) {
        darkColorScheme(
            primary = Color(0xFFE8E8E8),
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF262626),
            onPrimaryContainer = Color.White,
            secondary = Color(0xFFBDBDBD),
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF262626),
            onSecondaryContainer = Color.White,
            tertiary = Color(0xFFBDBDBD),
            background = Color.Black,
            onBackground = Color.White,
            surface = Color.Black,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1A1A1A),
            onSurfaceVariant = Color(0xFFBDBDBD),
            surfaceContainer = Color(0xFF121212),
            surfaceContainerHigh = Color(0xFF1A1A1A),
            surfaceContainerHighest = Color(0xFF262626),
            surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainerLowest = Color.Black,
            outline = Color(0xFF5A5A5A),
            outlineVariant = Color(0xFF333333),
        )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(LocalContext.current)
        else dynamicLightColorScheme(LocalContext.current)
    } else {
        // todo (later): more colors
        if (dark) darkColorScheme(
            primary = colorResource(R.color.accent),
        )
        else lightColorScheme(
            primary = colorResource(R.color.accent)
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(
            titleLarge = material3.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = material3.titleMedium.copy(fontWeight = FontWeight.Bold),
            titleSmall = material3.titleSmall.copy(fontWeight = FontWeight.Bold)
        ),
        //shapes = Shapes(),
        content = content
    )
}

const val previewDark = true
