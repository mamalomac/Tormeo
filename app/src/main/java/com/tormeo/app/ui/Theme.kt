package com.tormeo.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF7C5CFF)
val Bg = Color(0xFF0E0F13)
val Surface1 = Color(0xFF181A20)
val Surface2 = Color(0xFF22252D)
val TextDim = Color(0xFFA2A5B4)
val Good = Color(0xFF3DDC84)
val Warn = Color(0xFFFFB74D)
val Bad = Color(0xFFFF6B6B)

@Composable
fun TormeoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Bg,
            onBackground = Color.White,
            surface = Surface1,
            onSurface = Color.White,
            surfaceVariant = Surface2,
            onSurfaceVariant = TextDim,
            secondaryContainer = Surface2,
        ),
        content = content,
    )
}
