package com.idimus.simmusic

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Accent presets. Mono has no fixed color: it follows the text color (white in dark mode, near
 * black in light), so it carries `null` and is resolved per theme.
 */
enum class AccentPreset(val argb: Long?, val label: Int) {
    Pink(0xFFF0466E, R.string.accent_pink),
    Blue(0xFF3E82F7, R.string.accent_blue),
    Teal(0xFF14A3A3, R.string.accent_teal),
    Green(0xFF2EB872, R.string.accent_green),
    Orange(0xFFFF8A1F, R.string.accent_orange),
    Red(0xFFE5484D, R.string.accent_red),
    Purple(0xFF8C5CF6, R.string.accent_purple),
    Mono(null, R.string.accent_mono);

    fun color(dark: Boolean): Color = argb?.let { Color(it) } ?: if (dark) Color(0xFFFAFAFA) else Color(0xFF010101)
}

object Accent {
    var preset by mutableStateOf(AccentPreset.Pink)

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(context: Context) {
        preset = AccentPreset.entries.firstOrNull { it.name == prefs(context).getString("accent", null) } ?: AccentPreset.Pink
    }

    fun set(context: Context, p: AccentPreset) {
        preset = p
        prefs(context).edit().putString("accent", p.name).apply()
    }

    /** For places outside Compose (the widget), which follow the system night mode. */
    fun argb(context: Context): Int {
        load(context)
        val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return preset.color(dark).toArgb()
    }
}

/** App palette: grey canvas with white rounded cards in light mode, true black in dark. */
object SevenColors {
    val accent: Color @Composable get() = Accent.preset.color(isSystemInDarkTheme())

    /** Content on an accent fill: white, except on Mono where the fill itself is white or black. */
    val onAccent: Color @Composable get() = if (Accent.preset == AccentPreset.Mono) canvas() else Color.White

    @Composable fun canvas() = if (isSystemInDarkTheme()) Color(0xFF010101) else Color(0xFFF6F6F6)
    @Composable fun card() = if (isSystemInDarkTheme()) Color(0xFF171717) else Color.White
    @Composable fun text() = if (isSystemInDarkTheme()) Color(0xFFFAFAFA) else Color(0xFF010101)
    @Composable fun secondaryText() = if (isSystemInDarkTheme()) Color(0xFF9A9A9A) else Color(0xFF7A7A7A)
    @Composable fun artPlaceholder() = if (isSystemInDarkTheme()) Color(0xFF2A2A2A) else Color(0xFFE8E8E8)
    @Composable fun divider() = if (isSystemInDarkTheme()) Color(0xFF262626) else Color(0xFFEAEAEA)
}

@Composable
fun SevenTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = SevenColors.accent, background = Color(0xFF010101), surface = Color(0xFF171717))
    } else {
        lightColorScheme(primary = SevenColors.accent, background = Color(0xFFF6F6F6), surface = Color.White)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
