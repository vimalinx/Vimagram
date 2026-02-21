package com.clawdbot.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import com.clawdbot.android.chat.ChatSessionEntry
import com.clawdbot.android.ui.ManusColors
import com.clawdbot.android.ui.manusBorder

@Composable
fun ChatComposer(
  sessionKey: String,
  sessions: List<ChatSessionEntry>,
  mainSessionKey: String,
  healthOk: Boolean,
  thinkingLevel: String,
  pendingRunCount: Int,
  errorText: String?,
  attachments: List<PendingImageAttachment>,
  onPickImages: () -> Unit,
  onRemoveAttachment: (id: String) -> Unit,
  onSetThinkingLevel: (level: String) -> Unit,
  onSelectSession: (sessionKey: String) -> Unit,
  onRefresh: () -> Unit,
  onAbort: () -> Unit,
  onSend: (text: String) -> Unit,
) {
  val haptics = LocalHapticFeedback.current
  var input by rememberSaveable { mutableStateOf("") }
  var showThinkingMenu by remember { mutableStateOf(false) }
  var showSessionMenu by remember { mutableStateOf(false) }

  val sessionOptions = resolveSessionChoices(sessionKey, sessions, mainSessionKey = mainSessionKey)
  val currentSessionLabel =
    sessionOptions.firstOrNull { it.key == sessionKey }?.displayName ?: sessionKey

  val canSend = pendingRunCount == 0 && (input.trim().isNotEmpty() || attachments.isNotEmpty()) && healthOk

  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surface,
    border = manusBorder(alpha = 0.35f),
    tonalElevation = 0.dp,
    shadowElevation = 0.dp,
  ) {
    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box {
          Button(
            onClick = { showSessionMenu = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
            contentPadding = ButtonDefaults.ContentPadding,
          ) {
            Text("Session: $currentSessionLabel")
          }

          DropdownMenu(expanded = showSessionMenu, onDismissRequest = { showSessionMenu = false }) {
            for (entry in sessionOptions) {
              DropdownMenuItem(
                text = { Text(entry.displayName ?: entry.key) },
                onClick = {
                  onSelectSession(entry.key)
                  showSessionMenu = false
                },
                trailingIcon = {
                  if (entry.key == sessionKey) {
                    Text("✓")
                  } else {
                    Spacer(modifier = Modifier.width(10.dp))
                  }
                },
              )
            }
          }
        }

        Box {
          Button(
            onClick = { showThinkingMenu = true },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
            contentPadding = ButtonDefaults.ContentPadding,
          ) {
            Text("Thinking: ${thinkingLabel(thinkingLevel)}")
          }

          DropdownMenu(expanded = showThinkingMenu, onDismissRequest = { showThinkingMenu = false }) {
            ThinkingMenuItem("off", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
            ThinkingMenuItem("low", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
            ThinkingMenuItem("medium", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
            ThinkingMenuItem("high", thinkingLevel, onSetThinkingLevel) { showThinkingMenu = false }
          }
        }

        Spacer(modifier = Modifier.weight(1f))

        FilledIconButton(
          onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onRefresh()
          },
          modifier = Modifier.size(42.dp),
          colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
          Icon(Icons.Default.Refresh, contentDescription = "Refresh")
        }

        FilledIconButton(
          onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onPickImages()
          },
          modifier = Modifier.size(42.dp),
          colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
          Icon(Icons.Default.AttachFile, contentDescription = "Add image")
        }
      }

      if (attachments.isNotEmpty()) {
        AttachmentsStrip(attachments = attachments, onRemoveAttachment = onRemoveAttachment)
      }

      OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Message Clawd…") },
        minLines = 2,
        maxLines = 6,
        shape = MaterialTheme.shapes.large,
        colors =
          androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = MaterialTheme.colorScheme.primary,
          ),
      )

      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ConnectionPill(sessionLabel = currentSessionLabel, healthOk = healthOk)
        Spacer(modifier = Modifier.weight(1f))

        if (pendingRunCount > 0) {
          FilledIconButton(
            onClick = onAbort,
            colors =
              IconButtonDefaults.filledIconButtonColors(
                containerColor = ManusColors.Danger.copy(alpha = 0.20f),
                contentColor = ManusColors.Danger,
              ),
          ) {
            Icon(Icons.Default.Stop, contentDescription = "Abort")
          }
        } else {
          FilledIconButton(
            onClick = {
              val text = input
              input = ""
              haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              onSend(text)
            },
            enabled = canSend,
          ) {
            Icon(Icons.Default.ArrowUpward, contentDescription = "Send")
          }
        }
      }

      if (!errorText.isNullOrBlank()) {
        Text(
          text = errorText,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          maxLines = 2,
        )
      }
    }
  }
}

@Composable
private fun ConnectionPill(sessionLabel: String, healthOk: Boolean) {
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = MaterialTheme.colorScheme.surface,
    border = manusBorder(alpha = 0.35f),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        modifier = Modifier.size(7.dp),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (healthOk) ManusColors.Success else ManusColors.Warning,
      ) {}
      Text(sessionLabel, style = MaterialTheme.typography.labelSmall)
      Text(
        if (healthOk) "Connected" else "Connecting…",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ThinkingMenuItem(
  value: String,
  current: String,
  onSet: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  DropdownMenuItem(
    text = { Text(thinkingLabel(value)) },
    onClick = {
      onSet(value)
      onDismiss()
    },
    trailingIcon = {
      if (value == current.trim().lowercase()) {
        Text("✓")
      } else {
        Spacer(modifier = Modifier.width(10.dp))
      }
    },
  )
}

private fun thinkingLabel(raw: String): String {
  return when (raw.trim().lowercase()) {
    "low" -> "Low"
    "medium" -> "Medium"
    "high" -> "High"
    else -> "Off"
  }
}

@Composable
private fun AttachmentsStrip(
  attachments: List<PendingImageAttachment>,
  onRemoveAttachment: (id: String) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (att in attachments) {
      AttachmentChip(
        fileName = att.fileName,
        onRemove = { onRemoveAttachment(att.id) },
      )
    }
  }
}

@Composable
private fun AttachmentChip(fileName: String, onRemove: () -> Unit) {
  val haptics = LocalHapticFeedback.current
  Surface(
    shape = RoundedCornerShape(999.dp),
    color = MaterialTheme.colorScheme.surface,
    border = manusBorder(alpha = 0.35f),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(text = fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
      FilledIconButton(
        onClick = {
          haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          onRemove()
        },
        modifier = Modifier.size(30.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "Remove attachment",
        )
      }
    }
  }
}
