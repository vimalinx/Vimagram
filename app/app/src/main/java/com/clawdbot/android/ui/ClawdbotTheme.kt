package com.clawdbot.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.clawdbot.android.ui.ManusTheme

@Composable
fun ClawdbotTheme(content: @Composable () -> Unit) {
  ManusTheme(content = content)
}

@Composable
fun overlayContainerColor(): Color {
  val scheme = MaterialTheme.colorScheme
  val isDark = isSystemInDarkTheme()
  val base = if (isDark) scheme.surfaceContainerLow else scheme.surface
  return if (isDark) base else base.copy(alpha = 0.96f)
}

@Composable
fun overlayIconColor(): Color {
  return MaterialTheme.colorScheme.onSurfaceVariant
}
