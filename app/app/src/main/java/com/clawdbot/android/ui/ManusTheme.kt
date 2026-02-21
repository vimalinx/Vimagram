package com.clawdbot.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object ManusColors {
  val Success = Color(0xFF22C55E)
  val Warning = Color(0xFFF59E0B)
  val Danger = Color(0xFFDC2626)
  val Muted = Color(0xFF64748B)
}

internal object ManusSchemes {
  val Light =
    lightColorScheme(
      primary = Color(0xFF111111),
      onPrimary = Color(0xFFFFFFFF),
      secondary = Color(0xFF3B3B3B),
      onSecondary = Color(0xFFFFFFFF),
      background = Color(0xFFF5F6F8),
      onBackground = Color(0xFF111827),
      surface = Color(0xFFFFFFFF),
      onSurface = Color(0xFF111827),
      surfaceContainerLow = Color(0xFFF7F8FA),
      surfaceContainer = Color(0xFFF1F3F6),
      surfaceContainerHigh = Color(0xFFEEF2F6),
      surfaceContainerHighest = Color(0xFFE8ECF2),
      surfaceVariant = Color(0xFFF1F3F6),
      onSurfaceVariant = Color(0xFF6B7280),
      outline = Color(0xFFD1D5DB),
      error = Color(0xFFB42318),
      errorContainer = Color(0xFFFEE4E2),
      onErrorContainer = Color(0xFF912018),
    )

  val Dark =
    darkColorScheme(
      primary = Color(0xFFF2F4F7),
      onPrimary = Color(0xFF111111),
      secondary = Color(0xFFE4E7EC),
      onSecondary = Color(0xFF111111),
      background = Color(0xFF0F1113),
      onBackground = Color(0xFFF2F4F7),
      surface = Color(0xFF17191C),
      onSurface = Color(0xFFF2F4F7),
      surfaceContainerLow = Color(0xFF1B1E22),
      surfaceContainer = Color(0xFF1E2126),
      surfaceContainerHigh = Color(0xFF22262B),
      surfaceContainerHighest = Color(0xFF262A30),
      surfaceVariant = Color(0xFF22262B),
      onSurfaceVariant = Color(0xFF9CA3AF),
      outline = Color(0xFF3F4650),
      error = Color(0xFFF97066),
      errorContainer = Color(0xFF55160C),
      onErrorContainer = Color(0xFFFEE4E2),
    )
}

internal val ManusShapes =
  Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
  )

@Composable
fun ManusTheme(content: @Composable () -> Unit) {
  val scheme = if (isSystemInDarkTheme()) ManusSchemes.Dark else ManusSchemes.Light
  MaterialTheme(colorScheme = scheme, shapes = ManusShapes, content = content)
}

@Composable
fun manusBorder(alpha: Float = 0.35f): BorderStroke {
  return BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = alpha))
}
