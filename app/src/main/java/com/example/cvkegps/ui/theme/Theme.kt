package com.example.cvkegps.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF8F4C28),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA),
    onPrimaryContainer = Color(0xFF331200),
    secondaryContainer = Color(0xFFF3DFD3),
    onSecondaryContainer = Color(0xFF2C160B),
    background = Color(0xFFFFF8F4),
    surface = Color(0xFFFFF8F4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB68F),
    onPrimary = Color(0xFF532200),
    primaryContainer = Color(0xFF713514),
    onPrimaryContainer = Color(0xFFFFDBCA),
    secondaryContainer = Color(0xFF594236),
    onSecondaryContainer = Color(0xFFF3DFD3),
    background = Color(0xFF18120E),
    surface = Color(0xFF18120E),
)

@Composable
fun CvkeGpsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
