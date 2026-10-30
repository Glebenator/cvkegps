package com.example.cvkegps.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** The design-language colour tokens. Material roles map to them in [CvkeGpsTheme]. Brand colours are for brand art only. */
@Immutable
data class CvkeColors(
    val bg: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val border: Color,
    val borderControl: Color,
    val ink: Color,
    val inkMuted: Color,
    val accent: Color,
    val accentInk: Color,
    val accentSubtle: Color,
    /** Text and icons on [accentSubtle]. It is [accent] in the app colours. */
    val accentOnSubtle: Color,
    val brandApricot: Color,
    val brandApricotSubtle: Color,
    val gradientEnd: Color,
    val success: Color,
    val warning: Color,
    val warningSubtle: Color,
    val danger: Color,
    val dangerInk: Color,
    val dangerSubtle: Color,
    val scrim: Color,
)

internal val LightTokens = CvkeColors(
    bg = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFEEF0F3),
    border = Color(0xFFE1E4E9),
    borderControl = Color(0xFF858D99),
    ink = Color(0xFF161A1F),
    inkMuted = Color(0xFF5B6470),
    accent = Color(0xFF0A5CFF),
    accentInk = Color(0xFFFFFFFF),
    accentSubtle = Color(0xFFE5EDFF),
    accentOnSubtle = Color(0xFF0A5CFF),
    brandApricot = Color(0xFFD9661F),
    brandApricotSubtle = Color(0xFFFDE9DC),
    gradientEnd = Color(0xFFC08D96),
    success = Color(0xFF1A7F4B),
    warning = Color(0xFF9A5B00),
    warningSubtle = Color(0xFFFBF0DC),
    danger = Color(0xFFB42318),
    dangerInk = Color(0xFFFFFFFF),
    dangerSubtle = Color(0xFFFDECEA),
    scrim = Color(0x66161A1F),
)

internal val DarkTokens = CvkeColors(
    bg = Color(0xFF12161B),
    surface = Color(0xFF1A2027),
    surfaceSunken = Color(0xFF0D1014),
    border = Color(0xFF2A323C),
    borderControl = Color(0xFF6B7684),
    ink = Color(0xFFE9EDF1),
    inkMuted = Color(0xFF9FA9B4),
    accent = Color(0xFF6F9BFF),
    accentInk = Color(0xFF0A1633),
    accentSubtle = Color(0xFF132552),
    accentOnSubtle = Color(0xFF6F9BFF),
    brandApricot = Color(0xFFF5A578),
    brandApricotSubtle = Color(0xFF3A2012),
    gradientEnd = Color(0xFFDBA2A8),
    success = Color(0xFF5FD39A),
    warning = Color(0xFFF5B14C),
    warningSubtle = Color(0xFF33270F),
    danger = Color(0xFFF97066),
    dangerInk = Color(0xFF2A0A07),
    dangerSubtle = Color(0xFF3A1614),
    scrim = Color(0x9905070A),
)

/**
 * Data colours for a route coloured by speed, from slow to fast. They are the viridis scale: the lightness rises in order,
 * so the order also shows without colour vision. They stay the same in both themes and with wallpaper colours.
 */
object SpeedRamp {
    val stops = listOf(
        Color(0xFF440154), Color(0xFF443983), Color(0xFF31688E), Color(0xFF21918C), Color(0xFF35B779), Color(0xFF90D743), Color(0xFFFDE725),
    )

    /** A route part without a usable speed. */
    val noSpeed = Color(0xFF8A8F87)

    /** The outline under a speed-coloured route on a light map, and on a dark or satellite map. */
    val outlineOnLight = Color(0xFF161A1F)
    val outlineOnDark = Color(0xFFFFFFFF)

    /** The colour at [position], from 0 (slow) to 1 (fast). */
    fun at(position: Float): Color {
        val x = position.coerceIn(0f, 1f) * (stops.size - 1)
        val i = x.toInt().coerceAtMost(stops.size - 2)
        return lerp(stops[i], stops[i + 1], x - i)
    }
}

/** The 4 dp spacing steps. Use only these for gaps and padding. */
object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s12 = 48.dp
}

/** Corner radii. Touch: cards and sheets use [lg], buttons and chips [full], icon tiles and inputs [md]. */
object Radius {
    val sm = 4.dp
    val md = 8.dp
    val lg = 16.dp
}

/**
 * Motion: subtle and short. Animate only to show where something came from or went, to soften a jump, or to show work.
 * Tab changes and data values change at once. Nothing takes longer than 400 ms.
 */
object Motion {
    const val FEEDBACK_MILLIS = 100
    const val QUICK_MILLIS = 220
    const val ENTER_MILLIS = 340
    const val EXIT_MILLIS = 240

    /** `easing-standard`, cubic-bezier(0.4, 0, 0.2, 1): gentle at both ends. */
    val standard: Easing = FastOutSlowInEasing

    /** `move-md`: the travel of a screen that pushes in. */
    val moveMd = 8.dp
}

/** Fixed sizes. A touch target is 48 dp, which is the Android minimum and more than the design minimum of 44 dp. */
object Size {
    val iconSm = 16.dp
    val iconMd = 20.dp
    val tile = 32.dp
    val touch = 48.dp
}
