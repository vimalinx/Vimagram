package com.clawdbot.android.testchat.v2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clawdbot.android.ui.manusBorder

@Composable
internal fun V2Screen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  Box(
    modifier =
      modifier
        .background(MaterialTheme.colorScheme.background)
        .fillMaxWidth(),
  ) {
    content()
  }
}

@Composable
internal fun V2SectionTitle(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.Medium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
internal fun V2Card(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    border = manusBorder(alpha = 0.35f),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      content()
    }
  }
}

@Composable
internal fun V2InfoCard(text: String, modifier: Modifier = Modifier) {
  V2Card(modifier = modifier) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
internal fun V2ErrorCard(text: String, modifier: Modifier = Modifier) {
  Card(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(18.dp),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.30f)),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
      ),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Text(text = text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
internal fun V2PrimaryButton(
  text: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.fillMaxWidth(),
    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
  ) {
    Text(text)
  }
}

@Composable
internal fun V2SecondaryButton(
  text: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.fillMaxWidth(),
    border = manusBorder(alpha = 0.45f),
  ) {
    Text(text)
  }
}

@Composable
internal fun V2ModeSelector(
  title: String,
  options: List<V2ModeOption>,
  selectedId: String,
  onSelect: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    V2SectionTitle(text = title)
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      options.forEach { opt ->
        val isSelected = opt.id == selectedId
        if (isSelected) {
          Button(onClick = { onSelect(opt.id) }) { Text(opt.title) }
        } else {
          OutlinedButton(onClick = { onSelect(opt.id) }, border = manusBorder(alpha = 0.45f)) {
            Text(opt.title)
          }
        }
      }
    }
  }
}

internal data class V2ModeOption(
  val id: String,
  val title: String,
  val hint: String,
)

@Composable
internal fun V2Pill(
  text: String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .border(manusBorder(alpha = 0.28f), RoundedCornerShape(999.dp))
        .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
        .padding(horizontal = 10.dp, vertical = 5.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
internal fun V2Avatar(initial: String, color: Color, modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier
        .size(42.dp)
        .background(color.copy(alpha = 0.18f), CircleShape),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = initial,
      style = MaterialTheme.typography.titleMedium,
      color = color,
      maxLines = 1,
    )
  }
}

@Composable
internal fun V2DisclosureRow(
  title: String,
  subtitle: String,
  expanded: Boolean,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  Row(
    modifier = modifier.fillMaxWidth().clickable { onClick() },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(text = title, style = MaterialTheme.typography.titleSmall)
      if (subtitle.isNotBlank()) {
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    Spacer(modifier = Modifier.width(10.dp))
    Text(if (expanded) "–" else "+", style = MaterialTheme.typography.titleMedium)
  }
}
