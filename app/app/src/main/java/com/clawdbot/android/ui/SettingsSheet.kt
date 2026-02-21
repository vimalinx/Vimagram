package com.clawdbot.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.clawdbot.android.BuildConfig
import com.clawdbot.android.LocationMode
import com.clawdbot.android.MainViewModel
import com.clawdbot.android.NodeForegroundService
import com.clawdbot.android.UpdateStatus
import com.clawdbot.android.VoiceWakeMode
import com.clawdbot.android.WakeWords
import com.clawdbot.android.ui.manusBorder
import kotlinx.coroutines.launch

@Composable
private fun ManusSectionHeader(text: String) {
  Text(text = text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun ManusDivider() {
  HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
}

@Composable
private fun ManusCard(content: @Composable () -> Unit) {
  Card(
    shape = MaterialTheme.shapes.extraLarge,
    border = manusBorder(alpha = 0.35f),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      content()
    }
  }
}

@Composable
private fun ManusSwitchRow(
  title: String,
  description: String,
  checked: Boolean,
  enabled: Boolean = true,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.labelLarge)
      Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
  }
}

@Composable
private fun ManusRadioRow(
  title: String,
  description: String,
  selected: Boolean,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f).clickable(enabled = enabled) { onClick() },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.labelLarge)
      Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    RadioButton(selected = selected, onClick = onClick, enabled = enabled)
  }
}

@Composable
private fun ManusKeyValueRow(
  title: String,
  value: String,
  copyValue: String? = null,
  onCopied: (() -> Unit)? = null,
) {
  val haptics = LocalHapticFeedback.current
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.labelLarge)
      Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (!copyValue.isNullOrBlank() && onCopied != null) {
      val clipboard = LocalClipboardManager.current
      FilledIconButton(
        onClick = {
          haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
          clipboard.setText(AnnotatedString(copyValue))
          onCopied()
        },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.height(36.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.ContentCopy,
          contentDescription = "Copy $title",
        )
      }
    }
  }
}

@Composable
private fun ManusButton(
  label: String,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(label)
  }
}

@Composable
private fun ManusOutlinedField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  singleLine: Boolean = false,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    modifier = modifier.fillMaxWidth(),
    enabled = enabled,
    singleLine = singleLine,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
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
}

@Composable
fun SettingsSheet(viewModel: MainViewModel) {
  val context = LocalContext.current
  val haptics = LocalHapticFeedback.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  val showSnackbar: (String) -> Unit = { msg ->
    scope.launch { snackbarHostState.showSnackbar(message = msg, withDismissAction = true, duration = SnackbarDuration.Short) }
  }
  val instanceId by viewModel.instanceId.collectAsState()
  val displayName by viewModel.displayName.collectAsState()
  val cameraEnabled by viewModel.cameraEnabled.collectAsState()
  val locationMode by viewModel.locationMode.collectAsState()
  val locationPreciseEnabled by viewModel.locationPreciseEnabled.collectAsState()
  val preventSleep by viewModel.preventSleep.collectAsState()
  val wakeWords by viewModel.wakeWords.collectAsState()
  val voiceWakeMode by viewModel.voiceWakeMode.collectAsState()
  val voiceWakeStatusText by viewModel.voiceWakeStatusText.collectAsState()
  val isConnected by viewModel.isConnected.collectAsState()
  val manualEnabled by viewModel.manualEnabled.collectAsState()
  val manualHost by viewModel.manualHost.collectAsState()
  val manualPort by viewModel.manualPort.collectAsState()
  val manualTls by viewModel.manualTls.collectAsState()
  val canvasDebugStatusEnabled by viewModel.canvasDebugStatusEnabled.collectAsState()
  val statusText by viewModel.statusText.collectAsState()
  val serverName by viewModel.serverName.collectAsState()
  val remoteAddress by viewModel.remoteAddress.collectAsState()
  val gateways by viewModel.gateways.collectAsState()
  val discoveryStatusText by viewModel.discoveryStatusText.collectAsState()

  val listState = rememberLazyListState()
  val (wakeWordsText, setWakeWordsText) = remember { mutableStateOf("") }
  val (advancedExpanded, setAdvancedExpanded) = remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  var wakeWordsHadFocus by remember { mutableStateOf(false) }
  val deviceModel =
    remember {
      listOfNotNull(Build.MANUFACTURER, Build.MODEL)
        .joinToString(" ")
        .trim()
        .ifEmpty { "Android" }
    }
  val updateState by viewModel.updateState.collectAsState()
  val appVersion =
    remember {
      val versionName = BuildConfig.VERSION_NAME.trim().ifEmpty { "dev" }
      if (BuildConfig.DEBUG && !versionName.contains("dev", ignoreCase = true)) {
        "$versionName-dev"
      } else {
        versionName
      }
    }

  LaunchedEffect(wakeWords) { setWakeWordsText(wakeWords.joinToString(", ")) }
  val commitWakeWords = {
    val parsed = WakeWords.parseIfChanged(wakeWordsText, wakeWords)
    if (parsed != null) {
      viewModel.setWakeWords(parsed)
    }
  }

  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
      val cameraOk = perms[Manifest.permission.CAMERA] == true
      viewModel.setCameraEnabled(cameraOk)
    }

  var pendingLocationMode by remember { mutableStateOf<LocationMode?>(null) }
  var pendingPreciseToggle by remember { mutableStateOf(false) }

  val locationPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
      val fineOk = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
      val coarseOk = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
      val granted = fineOk || coarseOk
      val requestedMode = pendingLocationMode
      pendingLocationMode = null

      if (pendingPreciseToggle) {
        pendingPreciseToggle = false
        viewModel.setLocationPreciseEnabled(fineOk)
        return@rememberLauncherForActivityResult
      }

      if (!granted) {
        viewModel.setLocationMode(LocationMode.Off)
        return@rememberLauncherForActivityResult
      }

      if (requestedMode != null) {
        viewModel.setLocationMode(requestedMode)
        if (requestedMode == LocationMode.Always) {
          val backgroundOk =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
              PackageManager.PERMISSION_GRANTED
          if (!backgroundOk) {
            openAppSettings(context)
          }
        }
      }
    }

  val audioPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
      // Status text is handled by NodeRuntime.
    }

  val smsPermissionAvailable =
    remember {
      context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) == true
    }
  var smsPermissionGranted by
    remember {
      mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
          PackageManager.PERMISSION_GRANTED,
      )
    }
  val smsPermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      smsPermissionGranted = granted
      viewModel.refreshGatewayConnection()
    }

  fun setCameraEnabledChecked(checked: Boolean) {
    if (!checked) {
      viewModel.setCameraEnabled(false)
      return
    }

    val cameraOk =
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    if (cameraOk) {
      viewModel.setCameraEnabled(true)
    } else {
      permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
  }

  fun requestLocationPermissions(targetMode: LocationMode) {
    val fineOk =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarseOk =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (fineOk || coarseOk) {
      viewModel.setLocationMode(targetMode)
      if (targetMode == LocationMode.Always) {
        val backgroundOk =
          ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!backgroundOk) {
          openAppSettings(context)
        }
      }
    } else {
      pendingLocationMode = targetMode
      locationPermissionLauncher.launch(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
      )
    }
  }

  fun setPreciseLocationChecked(checked: Boolean) {
    if (!checked) {
      viewModel.setLocationPreciseEnabled(false)
      return
    }
    val fineOk =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (fineOk) {
      viewModel.setLocationPreciseEnabled(true)
    } else {
      pendingPreciseToggle = true
      locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
    }
  }

  val visibleGateways =
    if (isConnected && remoteAddress != null) {
      gateways.filterNot { "${it.host}:${it.port}" == remoteAddress }
    } else {
      gateways
    }

  val gatewayDiscoveryFooterText =
    if (visibleGateways.isEmpty()) {
      discoveryStatusText
    } else if (isConnected) {
      "Discovery active • ${visibleGateways.size} other gateway${if (visibleGateways.size == 1) "" else "s"} found"
    } else {
      "Discovery active • ${visibleGateways.size} gateway${if (visibleGateways.size == 1) "" else "s"} found"
    }

  Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
      state = listState,
      modifier =
        Modifier
          .fillMaxWidth()
          .fillMaxHeight()
          .imePadding()
          .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      // Order parity: Node → Gateway → Voice → Camera → Messaging → Location → Screen.
      item { ManusSectionHeader("Node") }
      item {
        ManusCard {
          ManusOutlinedField(
            value = displayName,
            onValueChange = viewModel::setDisplayName,
            label = "Name",
          )
          ManusKeyValueRow(
            title = "Instance ID",
            value = instanceId,
            copyValue = instanceId,
            onCopied = { showSnackbar("Instance ID copied") },
          )
          ManusKeyValueRow(title = "Device", value = deviceModel)
          ManusKeyValueRow(title = "Version", value = appVersion)
        }
      }
    item {
      ManusCard {
        val statusText =
          when (updateState.status) {
            UpdateStatus.Idle -> "Check for the latest GitHub release."
            UpdateStatus.Checking -> "Checking…"
            UpdateStatus.Ready -> if (updateState.isUpdateAvailable) "New version available" else "Up to date"
            UpdateStatus.Error -> updateState.error ?: "Update check failed"
          }
        Text("Update", style = MaterialTheme.typography.titleSmall)
        Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val checking = updateState.status == UpdateStatus.Checking
        ManusButton(label = if (checking) "Checking" else "Check", enabled = !checking, onClick = viewModel::checkForUpdates)
      }
    }
    if (updateState.status == UpdateStatus.Ready && updateState.isUpdateAvailable) {
      val htmlUrl = updateState.htmlUrl
      val notes = updateState.releaseNotes?.take(1200).orEmpty()
      item {
        ManusCard {
          Text(updateState.latestName ?: updateState.latestTag ?: "New release", style = MaterialTheme.typography.titleSmall)
          Text("Tap to view the release on GitHub.", color = MaterialTheme.colorScheme.onSurfaceVariant)
          if (notes.isNotBlank()) {
            Text(notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          ManusButton(
            label = "View",
            enabled = !htmlUrl.isNullOrBlank(),
            onClick = {
              if (!htmlUrl.isNullOrBlank()) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)))
              }
            },
          )
        }
      }
    }

      item { ManusDivider() }

    // Gateway
      item { ManusSectionHeader("Gateway") }
      item {
        ManusCard {
          ManusKeyValueRow(title = "Status", value = statusText)
          if (serverName != null) {
            ManusKeyValueRow(title = "Server", value = serverName!!)
          }
          if (remoteAddress != null) {
            ManusKeyValueRow(
              title = "Address",
              value = remoteAddress!!,
              copyValue = remoteAddress!!,
              onCopied = { showSnackbar("Address copied") },
            )
          }
        }
      }
      item {
        // UI sanity: "Disconnect" only when we have an active remote.
        if (isConnected && remoteAddress != null) {
          ManusCard {
          ManusButton(
            label = "Disconnect",
            onClick = {
              haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
              viewModel.disconnect()
              NodeForegroundService.stop(context)
              showSnackbar("Disconnected")
            },
          )
          }
        }
      }

      item { ManusDivider() }

    if (!isConnected || visibleGateways.isNotEmpty()) {
      item {
        Text(
          if (isConnected) "Other Gateways" else "Discovered Gateways",
          style = MaterialTheme.typography.titleSmall,
        )
      }
      if (!isConnected && visibleGateways.isEmpty()) {
        item { Text("No gateways found yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
      } else {
        items(items = visibleGateways, key = { it.stableId }) { gateway ->
          val detailLines =
            buildList {
              add("IP: ${gateway.host}:${gateway.port}")
              gateway.lanHost?.let { add("LAN: $it") }
              gateway.tailnetDns?.let { add("Tailnet: $it") }
              if (gateway.gatewayPort != null || gateway.canvasPort != null) {
                val gw = (gateway.gatewayPort ?: gateway.port).toString()
                val canvas = gateway.canvasPort?.toString() ?: "—"
                add("Ports: gw $gw · canvas $canvas")
              }
            }
          ManusCard {
            Text(gateway.name, style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
              detailLines.forEach { line ->
                Text(line, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
            ManusButton(
              label = "Connect",
              onClick = {
                NodeForegroundService.start(context)
                viewModel.connect(gateway)
              },
            )
          }
        }
      }
      item {
        Text(
          gatewayDiscoveryFooterText,
          modifier = Modifier.fillMaxWidth(),
          textAlign = TextAlign.Center,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    item { ManusDivider() }

    item {
      ManusCard {
        Row(
          modifier = Modifier.fillMaxWidth().clickable { setAdvancedExpanded(!advancedExpanded) },
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Advanced", style = MaterialTheme.typography.titleSmall)
            Text("Manual gateway connection", color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Icon(
            imageVector = if (advancedExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (advancedExpanded) "Collapse" else "Expand",
          )
        }
      }
    }
    item {
      AnimatedVisibility(visible = advancedExpanded) {
        ManusCard {
          ManusSwitchRow(
            title = "Use Manual Gateway",
            description = "Use this when discovery is blocked.",
            checked = manualEnabled,
            onCheckedChange = viewModel::setManualEnabled,
          )

          ManusOutlinedField(
            value = manualHost,
            onValueChange = viewModel::setManualHost,
            label = "Host",
            enabled = manualEnabled,
          )

          ManusOutlinedField(
            value = manualPort.toString(),
            onValueChange = { v -> viewModel.setManualPort(v.toIntOrNull() ?: 0) },
            label = "Port",
            enabled = manualEnabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          )

          ManusSwitchRow(
            title = "Require TLS",
            description = "Pin the gateway certificate on first connect.",
            checked = manualTls,
            enabled = manualEnabled,
            onCheckedChange = viewModel::setManualTls,
          )

          val hostOk = manualHost.trim().isNotEmpty()
          val portOk = manualPort in 1..65535
          ManusButton(
            label = "Connect (Manual)",
            enabled = manualEnabled && hostOk && portOk,
            onClick = {
              NodeForegroundService.start(context)
              viewModel.connectManual()
              showSnackbar("Connecting…")
            },
          )
        }
      }
    }

    item { ManusDivider() }

    // Voice
    item { ManusSectionHeader("Voice") }
    item {
      val enabled = voiceWakeMode != VoiceWakeMode.Off
      ManusCard {
        ManusSwitchRow(
          title = "Voice Wake",
          description = voiceWakeStatusText,
          checked = enabled,
          onCheckedChange = { on ->
            if (on) {
              val micOk =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                  PackageManager.PERMISSION_GRANTED
              if (!micOk) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                showSnackbar("Microphone permission required")
              }
              viewModel.setVoiceWakeMode(VoiceWakeMode.Foreground)
            } else {
              viewModel.setVoiceWakeMode(VoiceWakeMode.Off)
            }
          },
        )
      }
    }
    item {
      AnimatedVisibility(visible = voiceWakeMode != VoiceWakeMode.Off) {
        ManusCard {
          ManusRadioRow(
            title = "Foreground Only",
            description = "Listens only while Clawdbot is open.",
            selected = voiceWakeMode == VoiceWakeMode.Foreground,
            onClick = {
              val micOk =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                  PackageManager.PERMISSION_GRANTED
              if (!micOk) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                showSnackbar("Microphone permission required")
              }
              viewModel.setVoiceWakeMode(VoiceWakeMode.Foreground)
            },
          )
          ManusRadioRow(
            title = "Always",
            description = "Keeps listening in the background (shows a persistent notification).",
            selected = voiceWakeMode == VoiceWakeMode.Always,
            onClick = {
              val micOk =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                  PackageManager.PERMISSION_GRANTED
              if (!micOk) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                showSnackbar("Microphone permission required")
              }
              viewModel.setVoiceWakeMode(VoiceWakeMode.Always)
            },
          )
        }
      }
    }
    item {
      ManusCard {
        ManusOutlinedField(
          value = wakeWordsText,
          onValueChange = setWakeWordsText,
          label = "Wake Words (comma-separated)",
          modifier =
            Modifier.onFocusChanged { focusState ->
              if (focusState.isFocused) {
                wakeWordsHadFocus = true
              } else if (wakeWordsHadFocus) {
                wakeWordsHadFocus = false
                commitWakeWords()
              }
            },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions =
            KeyboardActions(
              onDone = {
                commitWakeWords()
                focusManager.clearFocus()
              },
            ),
        )
        ManusButton(label = "Reset defaults", onClick = viewModel::resetWakeWordsDefaults)
      }
    }
    item {
      Text(
        if (isConnected) {
          "Any node can edit wake words. Changes sync via the gateway."
        } else {
          "Connect to a gateway to sync wake words globally."
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    item { ManusDivider() }

    // Camera
    item { ManusSectionHeader("Camera") }
    item {
      ManusCard {
        ManusSwitchRow(
          title = "Allow Camera",
          description = "Allows the gateway to request photos or short video clips (foreground only).",
          checked = cameraEnabled,
          onCheckedChange = ::setCameraEnabledChecked,
        )
      }
    }
    item {
      Text(
        "Tip: grant Microphone permission for video clips with audio.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    item { ManusDivider() }

    // Messaging
    item { ManusSectionHeader("Messaging") }
    item {
      val buttonLabel =
        when {
          !smsPermissionAvailable -> "Unavailable"
          smsPermissionGranted -> "Manage"
          else -> "Grant"
        }
      ManusCard {
        Text("SMS Permission", style = MaterialTheme.typography.titleSmall)
        Text(
          if (smsPermissionAvailable) {
            "Allow the gateway to send SMS from this device."
          } else {
            "SMS requires a device with telephony hardware."
          },
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ManusButton(
          label = buttonLabel,
          enabled = smsPermissionAvailable,
          onClick = {
            if (!smsPermissionAvailable) return@ManusButton
            if (smsPermissionGranted) {
              openAppSettings(context)
              showSnackbar("Open app settings")
            } else {
              smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
              showSnackbar("SMS permission requested")
            }
          },
        )
      }
    }

    item { ManusDivider() }

    // Location
    item { ManusSectionHeader("Location") }
    item {
      ManusCard {
        ManusRadioRow(
          title = "Off",
          description = "Disable location sharing.",
          selected = locationMode == LocationMode.Off,
          onClick = { viewModel.setLocationMode(LocationMode.Off) },
        )
        ManusRadioRow(
          title = "While Using",
          description = "Only while Clawdbot is open.",
          selected = locationMode == LocationMode.WhileUsing,
          onClick = {
            requestLocationPermissions(LocationMode.WhileUsing)
            showSnackbar("Location permission requested")
          },
        )
        ManusRadioRow(
          title = "Always",
          description = "Allow background location (requires system permission).",
          selected = locationMode == LocationMode.Always,
          onClick = {
            requestLocationPermissions(LocationMode.Always)
            showSnackbar("Location permission requested")
          },
        )
        ManusDivider()
        ManusSwitchRow(
          title = "Precise Location",
          description = "Use precise GPS when available.",
          checked = locationPreciseEnabled,
          enabled = locationMode != LocationMode.Off,
          onCheckedChange = ::setPreciseLocationChecked,
        )
      }
    }
    item {
      Text(
        "Always may require Android Settings to allow background location.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    item { ManusDivider() }

    // Screen
    item { ManusSectionHeader("Screen") }
    item {
      ManusCard {
        ManusSwitchRow(
          title = "Prevent Sleep",
          description = "Keeps the screen awake while Clawdbot is open.",
          checked = preventSleep,
          onCheckedChange = viewModel::setPreventSleep,
        )
      }
    }

    item { ManusDivider() }

    // Debug
    item { ManusSectionHeader("Debug") }
    item {
      ManusCard {
        ManusSwitchRow(
          title = "Debug Canvas Status",
          description = "Show status text in the canvas when debug is enabled.",
          checked = canvasDebugStatusEnabled,
          onCheckedChange = viewModel::setCanvasDebugStatusEnabled,
        )
      }
    }

      item { Spacer(modifier = Modifier.height(20.dp)) }
    }

    SnackbarHost(
      hostState = snackbarHostState,
      modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp),
    )
  }
}

private fun openAppSettings(context: Context) {
  val intent =
    Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", context.packageName, null),
    )
  context.startActivity(intent)
}
