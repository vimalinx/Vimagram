package com.clawdbot.android.testchat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
  lightColorScheme(
    primary = Color(0xFF2AABEE),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF1B8EC5),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceContainerHighest = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF64748B),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
  )

private val DarkColors =
  darkColorScheme(
    primary = Color(0xFF6CCBFF),
    onPrimary = Color(0xFF00233A),
    secondary = Color(0xFF52A7D8),
    onSecondary = Color(0xFF001C2B),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE2E8F0),
    surfaceContainerHighest = Color(0xFF1F2937),
    surfaceVariant = Color(0xFF243041),
    onSurfaceVariant = Color(0xFF9FB0C7),
    error = Color(0xFFF87171),
    errorContainer = Color(0xFF3F1414),
    onErrorContainer = Color(0xFFFECACA),
  )

@Composable
fun TestChatTheme(content: @Composable () -> Unit) {
  val colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkColors else LightColors
  MaterialTheme(colorScheme = colorScheme, content = content)
}
