package com.clawdbot.android.testchat.v2

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.clawdbot.android.R
import com.clawdbot.android.testchat.TestChatConnectionState
import com.clawdbot.android.testchat.TestChatUiState
import com.clawdbot.android.ui.ManusColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val v2MachinePalette =
  listOf(
    Color(0xFF344054),
    Color(0xFF175CD3),
    Color(0xFF0E9384),
    Color(0xFF667085),
    Color(0xFF4F46E5),
    Color(0xFF1570EF),
    Color(0xFF3B3B3B),
  )

internal fun v2ResolveMachineColor(label: String): Color {
  if (label.isBlank()) return v2MachinePalette.first()
  val index = kotlin.math.abs(label.lowercase().hashCode()) % v2MachinePalette.size
  return v2MachinePalette[index]
}

private val v2TimeFormatter: DateTimeFormatter =
  DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

internal fun v2FormatTime(timestampMs: Long): String {
  return v2TimeFormatter.format(Instant.ofEpochMilli(timestampMs))
}

@Composable
internal fun v2ConnectionLabel(state: TestChatUiState): Pair<String, Color> {
  return when (state.connectionState) {
    TestChatConnectionState.Connected -> stringResource(R.string.status_connected) to ManusColors.Success
    TestChatConnectionState.Connecting -> stringResource(R.string.status_connecting) to ManusColors.Warning
    TestChatConnectionState.Error -> stringResource(R.string.status_error) to ManusColors.Danger
    TestChatConnectionState.Disconnected -> stringResource(R.string.status_disconnected) to ManusColors.Muted
  }
}
