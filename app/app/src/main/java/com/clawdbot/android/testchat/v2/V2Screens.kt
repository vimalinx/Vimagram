package com.clawdbot.android.testchat.v2

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clawdbot.android.R
import com.clawdbot.android.UpdateState
import com.clawdbot.android.UpdateStatus
import com.clawdbot.android.testchat.TestChatHost
import com.clawdbot.android.testchat.TestChatMessage
import com.clawdbot.android.testchat.TestChatModeCatalog
import com.clawdbot.android.testchat.TestChatModeOption
import com.clawdbot.android.testchat.TestChatSessionUsage
import com.clawdbot.android.testchat.TestChatTokenUsage
import com.clawdbot.android.testchat.TestChatThread
import com.clawdbot.android.testchat.TestChatUiState
import com.clawdbot.android.testchat.TestServerConfigState
import com.clawdbot.android.testchat.parseChatIdentity
import com.clawdbot.android.testchat.resolveSessionLabel
import com.clawdbot.android.ui.ManusColors
import com.clawdbot.android.ui.manusBorder
import java.util.UUID

private const val DEFAULT_SERVER_URL = "http://49.235.88.239:18788"
private const val UUID_PREFIX = "local"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(
  errorText: String?,
  deviceId: String,
  selectedModeId: String,
  modeOptions: List<TestChatModeOption>,
  inviteRequired: Boolean?,
  serverTestMessage: String?,
  serverTestSuccess: Boolean?,
  serverTestInProgress: Boolean,
  initialUserId: String?,
  initialServerUrl: String?,
  serverConfig: TestServerConfigState,
  onSelectMode: (String) -> Unit,
  onQuickStart: (serverUrl: String) -> Unit,
  onClearServerTest: () -> Unit,
  onRegister: (serverUrl: String, userId: String, inviteCode: String, password: String) -> Unit,
  onLogin: (serverUrl: String, userId: String, password: String) -> Unit,
  onTestServer: (serverUrl: String) -> Unit,
  onFetchServerConfig: (serverUrl: String) -> Unit,
) {
  val fixedServerUrl = DEFAULT_SERVER_URL
  var isLogin by rememberSaveable { mutableStateOf(false) }
  var inviteCode by rememberSaveable { mutableStateOf("") }
  var registerPassword by rememberSaveable { mutableStateOf("") }
  var registerUserId by rememberSaveable { mutableStateOf(initialUserId ?: "") }
  var loginUserId by rememberSaveable { mutableStateOf(initialUserId ?: "") }
  var loginPassword by rememberSaveable { mutableStateOf("") }

  val normalizedSelectedServer = fixedServerUrl.trim().removeSuffix("/")
  val configMatchesServer = serverConfig.serverUrl == normalizedSelectedServer
  val inviteRequirement = if (configMatchesServer) serverConfig.inviteRequired else inviteRequired
  val inviteIsRequired = inviteRequirement == true

  LaunchedEffect(Unit) {
    onClearServerTest()
    onFetchServerConfig(fixedServerUrl)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(text = stringResource(R.string.title_account), style = MaterialTheme.typography.titleLarge)
            Text(
              text = stringResource(R.string.account_welcome),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    },
    containerColor = Color.Transparent,
  ) { padding ->
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .padding(padding),
    ) {
      Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        AnimatedVisibility(visible = !errorText.isNullOrBlank(), enter = fadeIn(), exit = fadeOut()) {
          V2ErrorCard(text = errorText.orEmpty())
        }

        V2Card {
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = stringResource(R.string.info_quick_start_device, deviceId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          V2InfoCard(text = fixedServerUrl)

          if (!serverTestMessage.isNullOrBlank()) {
            if (serverTestSuccess == true) V2InfoCard(text = serverTestMessage) else V2ErrorCard(text = serverTestMessage)
          }

          val v2Modes =
            modeOptions.map { opt ->
              V2ModeOption(
                id = opt.id,
                title = opt.title,
                hint = "${opt.modelHint} · ${opt.agentHint}",
              )
            }
          V2ModeSelector(
            title = stringResource(R.string.title_model_mode),
            options = v2Modes,
            selectedId = selectedModeId,
            onSelect = onSelectMode,
          )

          val modeHint = TestChatModeCatalog.resolveMode(selectedModeId)
          V2InfoCard(
            text =
              stringResource(
                R.string.info_mode_hint,
                modeHint.modelHint,
                modeHint.agentHint,
                modeHint.skillsHint,
              ),
          )

          Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            V2SecondaryButton(
              text =
                if (serverTestInProgress) stringResource(R.string.status_testing)
                else stringResource(R.string.action_test_server),
              enabled = !serverTestInProgress,
              modifier = Modifier.weight(1f),
            ) {
              onTestServer(fixedServerUrl)
            }
            V2PrimaryButton(
              text = stringResource(R.string.action_quick_start),
              modifier = Modifier.weight(1f),
            ) {
              onQuickStart(fixedServerUrl)
            }
          }
        }

        V2Card {
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ToggleChip(
              text = stringResource(R.string.action_register),
              selected = !isLogin,
              onClick = { isLogin = false },
              modifier = Modifier.weight(1f),
            )
            ToggleChip(
              text = stringResource(R.string.action_login),
              selected = isLogin,
              onClick = { isLogin = true },
              modifier = Modifier.weight(1f),
            )
          }

          if (isLogin) {
            V2TextField(
              value = loginUserId,
              onValueChange = { loginUserId = it },
              label = stringResource(R.string.label_user_id),
              singleLine = true,
            )
            V2TextField(
              value = loginPassword,
              onValueChange = { loginPassword = it },
              label = stringResource(R.string.label_password),
              singleLine = true,
              isPassword = true,
            )
            V2PrimaryButton(
              text = stringResource(R.string.action_login),
              enabled = loginUserId.isNotBlank() && loginPassword.isNotBlank(),
            ) {
              onLogin(fixedServerUrl, loginUserId, loginPassword)
            }
          } else {
            V2TextField(
              value = registerUserId,
              onValueChange = { registerUserId = it },
              label = stringResource(R.string.label_user_id),
              singleLine = true,
            )
            V2TextField(
              value = inviteCode,
              onValueChange = { inviteCode = it },
              label =
                stringResource(
                  if (inviteIsRequired) R.string.label_invite_code else R.string.label_invite_code_optional,
                ),
              singleLine = true,
            )
            V2TextField(
              value = registerPassword,
              onValueChange = { registerPassword = it },
              label = stringResource(R.string.label_password),
              singleLine = true,
              isPassword = true,
            )
            V2PrimaryButton(
              text = stringResource(R.string.action_register),
              enabled =
                registerUserId.isNotBlank() &&
                  registerPassword.isNotBlank() &&
                  (!inviteIsRequired || inviteCode.isNotBlank()),
            ) {
              onRegister(fixedServerUrl, registerUserId, inviteCode, registerPassword)
            }
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatListScreen(
  state: TestChatUiState,
  isAuthenticated: Boolean,
  onOpenChat: (String) -> Unit,
  onNewChat: (String, String, String, String, String) -> Unit,
  onGenerateHost: (String, (String, String) -> Unit) -> Unit,
  onRenameThread: (String, String) -> Unit,
  onTogglePinThread: (String) -> Unit,
  onToggleArchiveThread: (String) -> Unit,
  onDeleteThread: (String) -> Unit,
  onRestoreThread: (String) -> Unit,
  onPurgeThread: (String) -> Unit,
  onRequireLogin: () -> Unit,
  selectedModeId: String,
  modeOptions: List<TestChatModeOption>,
  onSelectMode: (String) -> Unit,
  onCopy: (String) -> Unit,
) {
  var showNewChat by remember { mutableStateOf(false) }
  var showAddHost by remember { mutableStateOf(false) }
  var generatedHost by remember { mutableStateOf<Pair<String, String>?>(null) }

  var searchQuery by rememberSaveable { mutableStateOf("") }
  var showArchived by rememberSaveable { mutableStateOf(false) }
  var showDeleted by rememberSaveable { mutableStateOf(false) }

  var renameTarget by remember { mutableStateOf<TestChatThread?>(null) }
  var renameValue by rememberSaveable { mutableStateOf("") }
  var deleteTarget by remember { mutableStateOf<TestChatThread?>(null) }
  var purgeTarget by remember { mutableStateOf<TestChatThread?>(null) }

  LaunchedEffect(renameTarget?.chatId) {
    renameValue = renameTarget?.let { resolveSessionLabel(it) }.orEmpty()
  }

  val (connLabel, connColor) = v2ConnectionLabel(state)

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Box(
                modifier =
                  Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(connColor),
              )
              Text(
                text = connLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        },
        actions = {
          IconButton(onClick = { showAddHost = true }) {
            Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.action_add_host))
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    },
    floatingActionButton = {
      if (state.hosts.isNotEmpty()) {
        FloatingActionButton(onClick = { showNewChat = true }) {
          Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.action_new_chat))
        }
      }
    },
    containerColor = Color.Transparent,
  ) { padding ->
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .padding(padding),
    ) {
      Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        AnimatedVisibility(visible = state.errorText != null, enter = fadeIn(), exit = fadeOut()) {
          V2ErrorCard(text = state.errorText.orEmpty())
        }

        if (!isAuthenticated) {
          V2InfoCard(text = stringResource(R.string.info_login_required_to_chat))
          V2PrimaryButton(text = stringResource(R.string.action_login), onClick = onRequireLogin)
        } else if (state.hosts.isEmpty()) {
          V2InfoCard(text = stringResource(R.string.info_no_hosts))
        } else {
          HostStrip(hosts = state.hosts, sessionUsage = state.sessionUsage, onCopy = onCopy)
        }

        val v2Modes =
          modeOptions.map { opt ->
            V2ModeOption(
              id = opt.id,
              title = opt.title,
              hint = "${opt.modelHint} · ${opt.agentHint}",
            )
          }
        V2ModeSelector(
          title = stringResource(R.string.title_model_mode),
          options = v2Modes,
          selectedId = selectedModeId,
          onSelect = onSelectMode,
        )
        V2InfoCard(
          text =
            stringResource(
              R.string.info_mode_hint,
              TestChatModeCatalog.resolveMode(selectedModeId).modelHint,
              TestChatModeCatalog.resolveMode(selectedModeId).agentHint,
              TestChatModeCatalog.resolveMode(selectedModeId).skillsHint,
            ),
        )

        if (state.threads.isNotEmpty()) {
          V2TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = stringResource(R.string.label_search_sessions),
            singleLine = true,
          )
        }

        val filteredThreads =
          if (searchQuery.isBlank()) {
            state.threads
          } else {
            val query = searchQuery.trim().lowercase()
            state.threads.filter { thread ->
              val identity = parseChatIdentity(thread.chatId)
              val title = resolveSessionLabel(thread).lowercase()
              val machine = identity.machine.lowercase()
              val session = identity.session.lowercase()
              title.contains(query) || machine.contains(query) || session.contains(query)
            }
          }

        val deletedThreads = filteredThreads.filter { it.isDeleted }
        val activeThreads = filteredThreads.filterNot { it.isArchived || it.isDeleted }
        val archivedThreads = filteredThreads.filter { it.isArchived && !it.isDeleted }

        val sortedActiveThreads =
          activeThreads.sortedWith(compareByDescending<TestChatThread> { it.isPinned }.thenByDescending { it.lastTimestampMs })
        val sortedArchivedThreads =
          archivedThreads.sortedWith(compareByDescending<TestChatThread> { it.isPinned }.thenByDescending { it.lastTimestampMs })
        val sortedDeletedThreads = deletedThreads.sortedByDescending { it.deletedAt ?: it.lastTimestampMs }

        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          items(sortedActiveThreads) { thread ->
            ThreadRow(
              thread = thread,
              onClick = { onOpenChat(thread.chatId) },
              onRename = { renameTarget = thread },
              onTogglePinned = { onTogglePinThread(thread.chatId) },
              onToggleArchived = { onToggleArchiveThread(thread.chatId) },
              onDelete = { deleteTarget = thread },
            )
          }

          if (sortedArchivedThreads.isNotEmpty()) {
            item {
              V2Card {
                V2DisclosureRow(
                  title = stringResource(R.string.action_show_archived, sortedArchivedThreads.size),
                  subtitle = "",
                  expanded = showArchived,
                  onClick = { showArchived = !showArchived },
                )
                AnimatedVisibility(visible = showArchived) {
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sortedArchivedThreads.forEach { t ->
                      ThreadRow(
                        thread = t,
                        onClick = { onOpenChat(t.chatId) },
                        onRename = { renameTarget = t },
                        onTogglePinned = { onTogglePinThread(t.chatId) },
                        onToggleArchived = { onToggleArchiveThread(t.chatId) },
                        onDelete = { deleteTarget = t },
                      )
                    }
                  }
                }
              }
            }
          }

          if (sortedDeletedThreads.isNotEmpty()) {
            item {
              V2Card {
                V2DisclosureRow(
                  title = stringResource(R.string.action_show_deleted, sortedDeletedThreads.size),
                  subtitle = "",
                  expanded = showDeleted,
                  onClick = { showDeleted = !showDeleted },
                )
                AnimatedVisibility(visible = showDeleted) {
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sortedDeletedThreads.forEach { t ->
                      DeletedThreadRow(
                        thread = t,
                        onRestore = { onRestoreThread(t.chatId) },
                        onDeleteForever = { purgeTarget = t },
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  if (renameTarget != null) {
    val target = renameTarget
    AlertDialog(
      onDismissRequest = { renameTarget = null },
      title = { Text(stringResource(R.string.title_rename_session)) },
      text = {
        V2TextField(
          value = renameValue,
          onValueChange = { renameValue = it },
          label = stringResource(R.string.label_session_name),
          singleLine = true,
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            if (target != null) onRenameThread(target.chatId, renameValue)
            renameTarget = null
          },
          enabled = renameValue.isNotBlank(),
        ) {
          Text(stringResource(R.string.action_save))
        }
      },
      dismissButton = {
        TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.action_cancel)) }
      },
    )
  }

  if (deleteTarget != null) {
    val target = deleteTarget
    AlertDialog(
      onDismissRequest = { deleteTarget = null },
      title = { Text(stringResource(R.string.title_delete_session)) },
      text = { Text(stringResource(R.string.msg_delete_session)) },
      confirmButton = {
        TextButton(
          onClick = {
            if (target != null) onDeleteThread(target.chatId)
            deleteTarget = null
          },
        ) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) }
      },
    )
  }

  if (purgeTarget != null) {
    val target = purgeTarget
    AlertDialog(
      onDismissRequest = { purgeTarget = null },
      title = { Text(stringResource(R.string.title_delete_forever)) },
      text = { Text(stringResource(R.string.msg_delete_forever)) },
      confirmButton = {
        TextButton(
          onClick = {
            if (target != null) onPurgeThread(target.chatId)
            purgeTarget = null
          },
        ) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { purgeTarget = null }) { Text(stringResource(R.string.action_cancel)) }
      },
    )
  }

  if (showAddHost) {
    var newHostLabel by rememberSaveable { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showAddHost = false },
      title = { Text(stringResource(R.string.title_add_host)) },
      text = {
        V2TextField(
          value = newHostLabel,
          onValueChange = { newHostLabel = it },
          label = stringResource(R.string.label_host_name),
          singleLine = true,
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onGenerateHost(newHostLabel) { label, token ->
              generatedHost = label to token
            }
            showAddHost = false
          },
          enabled = newHostLabel.isNotBlank(),
        ) {
          Text(stringResource(R.string.action_generate_token))
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddHost = false }) { Text(stringResource(R.string.action_cancel)) }
      },
    )
  }

  if (generatedHost != null) {
    val info = generatedHost
    AlertDialog(
      onDismissRequest = { generatedHost = null },
      title = { Text(stringResource(R.string.title_host_token)) },
      text = {
        Text(
          text =
            stringResource(
              R.string.msg_host_token,
              info?.first.orEmpty(),
              info?.second.orEmpty(),
            ),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            val token = info?.second.orEmpty()
            if (token.isNotBlank()) onCopy(token)
          },
        ) { Text(stringResource(R.string.action_copy_token)) }
      },
      dismissButton = {
        TextButton(onClick = { generatedHost = null }) { Text(stringResource(R.string.action_ok)) }
      },
    )
  }

  if (showNewChat) {
    var newChatTitle by rememberSaveable { mutableStateOf("") }
    var newChatSession by rememberSaveable { mutableStateOf("") }
    var newChatHost by rememberSaveable { mutableStateOf("") }
    var modelTierId by rememberSaveable { mutableStateOf("glm-4.7") }
    var identityId by rememberSaveable { mutableStateOf("ecom") }
    val hostOptions = state.hosts.map { it.label }
    val tierOptions =
      listOf(
        PickerOption("m2.5", stringResource(R.string.instance_tier_standard_long)),
        PickerOption("glm-4.7", stringResource(R.string.instance_tier_pro_long)),
        PickerOption("glm-5", stringResource(R.string.instance_tier_max_long)),
      )
    val identityOptions =
      listOf(
        PickerOption("ecom", stringResource(R.string.instance_profile_ecom)),
        PickerOption("docs", stringResource(R.string.instance_profile_docs)),
        PickerOption("media", stringResource(R.string.instance_profile_media)),
      )
    val fallbackHost = hostOptions.firstOrNull().orEmpty()
    LaunchedEffect(hostOptions) {
      if (newChatHost.isBlank() && fallbackHost.isNotBlank()) newChatHost = fallbackHost
    }

    AlertDialog(
      onDismissRequest = { showNewChat = false },
      title = { Text(stringResource(R.string.title_new_chat)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          V2TextField(
            value = newChatTitle,
            onValueChange = { newChatTitle = it },
            label = stringResource(R.string.label_title),
            singleLine = true,
          )
          V2TextField(
            value = newChatSession,
            onValueChange = { newChatSession = it },
            label = stringResource(R.string.label_session_name_optional),
            singleLine = true,
          )
          ChipPicker(
            label = stringResource(R.string.label_host),
            options = hostOptions.map { PickerOption(it, it) },
            selected = newChatHost,
            onSelected = { newChatHost = it },
          )
          ChipPicker(
            label = stringResource(R.string.label_model_tier),
            options = tierOptions,
            selected = modelTierId,
            onSelected = { modelTierId = it },
          )
          ChipPicker(
            label = stringResource(R.string.label_profile),
            options = identityOptions,
            selected = identityId,
            onSelected = { identityId = it },
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val session =
              if (newChatSession.isNotBlank()) newChatSession.trim()
              else "session-${UUID_PREFIX}${System.currentTimeMillis()}"
            onNewChat(newChatTitle, newChatHost, session, modelTierId, identityId)
            showNewChat = false
          },
          enabled = newChatHost.isNotBlank(),
        ) { Text(stringResource(R.string.action_create)) }
      },
      dismissButton = {
        TextButton(onClick = { showNewChat = false }) { Text(stringResource(R.string.action_cancel)) }
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(
  state: TestChatUiState,
  onBack: () -> Unit,
  onSend: (String) -> Unit,
  selectedModeId: String,
  modeOptions: List<TestChatModeOption>,
  onSelectMode: (String) -> Unit,
) {
  val chatId = state.activeChatId ?: return
  val thread = state.threads.firstOrNull { it.chatId == chatId }
  val identity = remember(chatId) { parseChatIdentity(chatId) }
  val sessionLabel = thread?.let { resolveSessionLabel(it) } ?: chatId
  val machineLabel = identity.machine
  val machineColor = v2ResolveMachineColor(machineLabel)
  val instanceLabel = resolveInstanceLabel(thread?.instanceModelTierId, thread?.instanceIdentityId)

  var message by rememberSaveable(chatId) { mutableStateOf("") }
  val listState = rememberLazyListState()
  val markdown = rememberV2Markwon(fontSize = MaterialTheme.typography.bodyMedium.fontSize)
  var didInitialScroll by remember(chatId) { mutableStateOf(false) }

  BackHandler(enabled = true) { onBack() }

  LaunchedEffect(chatId, state.messages.size) {
    if (state.messages.isNotEmpty()) {
      if (!didInitialScroll) {
        listState.scrollToItem(state.messages.size - 1)
        didInitialScroll = true
      } else {
        listState.animateScrollToItem(state.messages.size - 1)
      }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = sessionLabel,
              style = MaterialTheme.typography.titleLarge,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              V2Pill(text = machineLabel, color = machineColor)
              if (instanceLabel != null) {
                V2Pill(text = instanceLabel, color = MaterialTheme.colorScheme.primary)
              } else {
                val mode = TestChatModeCatalog.resolveMode(selectedModeId)
                V2Pill(text = mode.title, color = MaterialTheme.colorScheme.primary)
              }
            }
            val (label, color) = v2ConnectionLabel(state)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Box(
                modifier =
                  Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
              )
              Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    },
    containerColor = Color.Transparent,
  ) { padding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .padding(padding)
          .imePadding()
          .navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (state.errorText != null) {
        V2ErrorCard(text = state.errorText.orEmpty(), modifier = Modifier.padding(horizontal = 16.dp))
      }

      val v2Modes =
        modeOptions.map { opt ->
          V2ModeOption(
            id = opt.id,
            title = opt.title,
            hint = "${opt.modelHint} · ${opt.agentHint}",
          )
        }
      Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        V2ModeSelector(
          title = stringResource(R.string.title_model_mode),
          options = v2Modes,
          selectedId = selectedModeId,
          onSelect = onSelectMode,
        )
        val modeHint = TestChatModeCatalog.resolveMode(selectedModeId)
        V2InfoCard(
          text =
            stringResource(
              R.string.info_mode_hint,
              modeHint.modelHint,
              modeHint.agentHint,
              modeHint.skillsHint,
            ),
        )
      }

      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(state.messages) { item ->
          MessageBubble(message = item, markdown = markdown)
        }
      }

      V2Composer(
        value = message,
        onValueChange = { message = it },
        onSend = {
          onSend(message)
          message = ""
        },
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountDashboardScreen(
  state: TestChatUiState,
  onLogout: () -> Unit,
  languageTag: String,
  onLanguageChange: (String) -> Unit,
  updateState: UpdateState,
  onCheckUpdates: () -> Unit,
  onCopy: (String) -> Unit,
  onOpenUrl: (String) -> Unit,
) {
  val account = state.account
  val userLabel = account?.userId ?: stringResource(R.string.label_unknown_user)
  val serverLabel = account?.serverUrl ?: stringResource(R.string.label_unknown_server)
  var showLogoutConfirm by remember { mutableStateOf(false) }
  var showSettingsSheet by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.title_account)) },
        actions = {
          IconButton(onClick = { showSettingsSheet = true }) {
            Icon(imageVector = Icons.Default.MoreVert, contentDescription = stringResource(R.string.title_settings))
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    },
    containerColor = Color.Transparent,
  ) { padding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .padding(padding)
          .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!state.errorText.isNullOrBlank()) {
        V2ErrorCard(text = state.errorText.orEmpty())
      }

      V2SectionTitle(text = stringResource(R.string.title_account_section))
      V2Card {
        Text(text = userLabel, style = MaterialTheme.typography.titleMedium)
        Text(text = serverLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      V2SectionTitle(text = stringResource(R.string.title_host_tokens))
      if (state.hosts.isEmpty()) {
        V2InfoCard(text = stringResource(R.string.info_no_hosts_connected))
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          state.hosts.forEach { host ->
            V2Card {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val c = v2ResolveMachineColor(host.label)
                V2Avatar(initial = host.label.trim().take(1).uppercase(), color = c)
                Column(modifier = Modifier.weight(1f)) {
                  Text(host.label, style = MaterialTheme.typography.titleSmall)
                  Text(
                    host.token,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                OutlinedButton(onClick = { onCopy(host.token) }, border = manusBorder(alpha = 0.45f)) {
                  Text(stringResource(R.string.action_copy_token))
                }
              }
            }
          }
        }
      }


      V2SectionTitle(text = stringResource(R.string.title_language))
      LanguagePickerRow(selectedTag = languageTag, onSelected = onLanguageChange)

      Spacer(modifier = Modifier.height(6.dp))
      V2PrimaryButton(text = stringResource(R.string.action_logout), onClick = { showLogoutConfirm = true })
    }
  }

  if (showLogoutConfirm) {
    AlertDialog(
      onDismissRequest = { showLogoutConfirm = false },
      title = { Text(stringResource(R.string.title_logout_confirm)) },
      text = { Text(stringResource(R.string.msg_logout_confirm)) },
      confirmButton = {
        TextButton(
          onClick = {
            showLogoutConfirm = false
            onLogout()
          },
        ) { Text(stringResource(R.string.action_logout)) }
      },
      dismissButton = {
        TextButton(onClick = { showLogoutConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
      },
    )
  }

  if (showSettingsSheet) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
      onDismissRequest = { showSettingsSheet = false },
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.background,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        V2SectionTitle(text = stringResource(R.string.title_updates))
        V2Card {
          val statusText =
            when (updateState.status) {
              UpdateStatus.Idle -> stringResource(R.string.status_update_idle)
              UpdateStatus.Checking -> stringResource(R.string.status_update_checking)
              UpdateStatus.Ready ->
                if (updateState.isUpdateAvailable) stringResource(R.string.status_update_available)
                else stringResource(R.string.status_update_uptodate)
              UpdateStatus.Error -> updateState.error ?: stringResource(R.string.status_update_failed)
            }
          Text(statusText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
          val checking = updateState.status == UpdateStatus.Checking
          V2PrimaryButton(
            text =
              if (checking) stringResource(R.string.action_check_updates_working)
              else stringResource(R.string.action_check_updates),
            enabled = !checking,
            onClick = onCheckUpdates,
          )

          if (updateState.status == UpdateStatus.Ready && updateState.isUpdateAvailable) {
            val releaseTitle = updateState.latestName ?: updateState.latestTag ?: stringResource(R.string.label_update_release)
            val htmlUrl = updateState.htmlUrl.orEmpty()
            Text(releaseTitle, style = MaterialTheme.typography.titleSmall)
            val notes = updateState.releaseNotes?.trim()?.take(800).orEmpty()
            if (notes.isNotBlank()) {
              Text(notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            V2SecondaryButton(
              text = stringResource(R.string.action_view_release),
              enabled = htmlUrl.isNotBlank(),
              onClick = { if (htmlUrl.isNotBlank()) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl))) },
            )
          }
        }

        V2SectionTitle(text = stringResource(R.string.title_links))
        V2Card {
          val xhsUrl = "https://xhslink.com/m/487YEE3Jygk"
          val siteUrl = "https://github.com/vimalinx/ClawNet"
          V2SecondaryButton(text = stringResource(R.string.label_xhs), onClick = { onOpenUrl(xhsUrl) })
          V2SecondaryButton(text = stringResource(R.string.label_website), onClick = { onOpenUrl(siteUrl) })
          Text(siteUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevicesScreen(
  state: TestChatUiState,
  isAuthenticated: Boolean,
  onRequireLogin: () -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(text = stringResource(R.string.tab_devices)) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )
    },
    containerColor = Color.Transparent,
  ) { padding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.background)
          .padding(padding)
          .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!isAuthenticated) {
        V2InfoCard(text = stringResource(R.string.info_login_required_to_chat))
        V2PrimaryButton(text = stringResource(R.string.action_login), onClick = onRequireLogin)
      } else {
        DeviceStatsContent(state = state)
        Spacer(modifier = Modifier.height(12.dp))
      }
    }
  }
}

@Composable
private fun DeviceStatsContent(state: TestChatUiState) {
  val usageByToken = state.tokenUsage
  val hosts = state.hosts
  val sessions = state.sessionUsage.sortedByDescending { it.lastTimestampMs }
  val totalTokens = sessions.sumOf { it.tokenCount }

  V2Card {
    Text(text = stringResource(R.string.label_total_tokens, totalTokens), style = MaterialTheme.typography.bodyMedium)
    Text(text = stringResource(R.string.label_sessions_count, sessions.size), style = MaterialTheme.typography.bodyMedium)
  }

  if (hosts.isEmpty()) {
    V2InfoCard(text = stringResource(R.string.info_no_hosts_connected))
    return
  }

  V2SectionTitle(text = "Hosts")
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    hosts.forEach { host ->
      val usage = usageByToken[host.token]
      HostStatsCard(host = host, usage = usage)
    }
  }

  if (sessions.isNotEmpty()) {
    V2SectionTitle(text = "Sessions")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      sessions.forEach { usage ->
        SessionUsageCardV2(usage = usage)
      }
    }
  }
}

@Composable
private fun HostStatsCard(host: TestChatHost, usage: TestChatTokenUsage?) {
  val c = v2ResolveMachineColor(host.label)
  val lastSeen = usage?.lastSeenAt?.let { v2FormatTime(it) } ?: stringResource(R.string.label_none)
  val lastIn = usage?.lastInboundAt?.let { v2FormatTime(it) } ?: stringResource(R.string.label_none)
  val lastOut = usage?.lastOutboundAt?.let { v2FormatTime(it) } ?: stringResource(R.string.label_none)
  val streams = usage?.streamConnects ?: 0
  val inbound = usage?.inboundCount ?: 0
  val outbound = usage?.outboundCount ?: 0

  V2Card {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      V2Avatar(initial = host.label.trim().take(1).uppercase(), color = c)
      Column(modifier = Modifier.weight(1f)) {
        Text(host.label, style = MaterialTheme.typography.titleSmall)
        Text(
          text = stringResource(R.string.label_token_value, host.token),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      V2Pill(text = "${inbound} in / ${outbound} out", color = MaterialTheme.colorScheme.primary)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
      StatMini(title = "Streams", value = streams.toString(), modifier = Modifier.weight(1f))
      StatMini(title = "Seen", value = lastSeen, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
      StatMini(title = "Last in", value = lastIn, modifier = Modifier.weight(1f))
      StatMini(title = "Last out", value = lastOut, modifier = Modifier.weight(1f))
    }
  }
}

@Composable
private fun StatMini(title: String, value: String, modifier: Modifier = Modifier) {
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.surface)
        .border(manusBorder(alpha = 0.30f), RoundedCornerShape(14.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
  }
}

@Composable
private fun SessionUsageCardV2(usage: TestChatSessionUsage) {
  val hostColor = v2ResolveMachineColor(usage.hostLabel)
  val lastTime = if (usage.lastTimestampMs > 0) v2FormatTime(usage.lastTimestampMs) else stringResource(R.string.label_none)
  V2Card {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = usage.sessionLabel,
        style = MaterialTheme.typography.titleSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = lastTime,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      V2Pill(text = usage.hostLabel, color = hostColor)
      Text(
        text = stringResource(R.string.label_tokens_short, usage.tokenCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun HostStrip(
  hosts: List<TestChatHost>,
  sessionUsage: List<TestChatSessionUsage>,
  onCopy: (String) -> Unit,
) {
  if (hosts.isEmpty()) return
  val usageByHost = sessionUsage.groupBy { it.hostLabel }.mapValues { entry -> entry.value.sumOf { it.tokenCount } }
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    hosts.forEach { host ->
      val tokens = usageByHost[host.label] ?: 0
      Card(
        shape = RoundedCornerShape(16.dp),
        border = manusBorder(alpha = 0.35f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.widthIn(min = 140.dp),
      ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          val c = v2ResolveMachineColor(host.label)
          V2Pill(text = host.label, color = c)
          Text(
            text = stringResource(R.string.label_tokens_short, tokens),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedButton(onClick = { onCopy(host.token) }, border = manusBorder(alpha = 0.45f)) {
            Text(stringResource(R.string.action_copy_token))
          }
        }
      }
    }
  }
}

@Composable
private fun ThreadRow(
  thread: TestChatThread,
  onClick: () -> Unit,
  onRename: () -> Unit,
  onTogglePinned: () -> Unit,
  onToggleArchived: () -> Unit,
  onDelete: () -> Unit,
) {
  val identity = parseChatIdentity(thread.chatId)
  val sessionLabel = resolveSessionLabel(thread)
  val machineLabel = identity.machine
  val machineColor = v2ResolveMachineColor(machineLabel)
  val instanceLabel = resolveInstanceLabel(thread.instanceModelTierId, thread.instanceIdentityId)
  var menuExpanded by remember { mutableStateOf(false) }

  Card(
    shape = RoundedCornerShape(20.dp),
    border = manusBorder(alpha = 0.35f),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    modifier = Modifier.fillMaxWidth().clickable { onClick() },
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      V2Avatar(initial = sessionLabel.trim().take(1).uppercase(), color = machineColor)
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = sessionLabel,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          if (thread.isPinned) {
            Icon(
              imageVector = Icons.Default.PushPin,
              contentDescription = stringResource(R.string.label_pinned),
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
          }
          Text(
            text = v2FormatTime(thread.lastTimestampMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          V2Pill(text = machineLabel, color = machineColor)
          if (instanceLabel != null) {
            Spacer(modifier = Modifier.width(8.dp))
            V2Pill(text = instanceLabel, color = MaterialTheme.colorScheme.primary)
          }
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = thread.lastMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          if (thread.unreadCount > 0) {
            Box(
              modifier =
                Modifier
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primary)
                  .padding(horizontal = 8.dp, vertical = 2.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = thread.unreadCount.toString(),
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onPrimary),
              )
            }
          }
        }
      }
      Box {
        IconButton(onClick = { menuExpanded = true }) {
          Icon(imageVector = Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.action_rename)) },
            onClick = {
              menuExpanded = false
              onRename()
            },
            leadingIcon = { Icon(imageVector = Icons.Default.Edit, contentDescription = null) },
          )
          DropdownMenuItem(
            text = {
              Text(if (thread.isPinned) stringResource(R.string.action_unpin) else stringResource(R.string.action_pin))
            },
            onClick = {
              menuExpanded = false
              onTogglePinned()
            },
            leadingIcon = { Icon(imageVector = Icons.Default.PushPin, contentDescription = null) },
          )
          DropdownMenuItem(
            text = {
              Text(
                if (thread.isArchived) stringResource(R.string.action_unarchive) else stringResource(R.string.action_archive),
              )
            },
            onClick = {
              menuExpanded = false
              onToggleArchived()
            },
            leadingIcon = {
              Icon(
                imageVector = if (thread.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                contentDescription = null,
              )
            },
          )
          DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            onClick = {
              menuExpanded = false
              onDelete()
            },
            leadingIcon = { Icon(imageVector = Icons.Default.Delete, contentDescription = null) },
          )
        }
      }
    }
  }
}

@Composable
private fun DeletedThreadRow(
  thread: TestChatThread,
  onRestore: () -> Unit,
  onDeleteForever: () -> Unit,
) {
  val identity = parseChatIdentity(thread.chatId)
  val sessionLabel = resolveSessionLabel(thread)
  val machineLabel = identity.machine
  val machineColor = v2ResolveMachineColor(machineLabel)
  val instanceLabel = resolveInstanceLabel(thread.instanceModelTierId, thread.instanceIdentityId)
  val deletedAt = thread.deletedAt ?: thread.lastTimestampMs

  Card(
    shape = RoundedCornerShape(20.dp),
    border = manusBorder(alpha = 0.35f),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        V2Avatar(initial = sessionLabel.trim().take(1).uppercase(), color = machineColor)
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = sessionLabel,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            Text(
              text = v2FormatTime(deletedAt),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Spacer(modifier = Modifier.height(4.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            V2Pill(text = machineLabel, color = machineColor)
            if (instanceLabel != null) {
              Spacer(modifier = Modifier.width(8.dp))
              V2Pill(text = instanceLabel, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = thread.lastMessage,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onRestore, modifier = Modifier.weight(1f), border = manusBorder(alpha = 0.45f)) {
          Text(stringResource(R.string.action_restore))
        }
        OutlinedButton(onClick = onDeleteForever, modifier = Modifier.weight(1f), border = manusBorder(alpha = 0.45f)) {
          Text(stringResource(R.string.action_delete_forever))
        }
      }
    }
  }
}

private data class PickerOption(val id: String, val label: String)

@Composable
private fun ChipPicker(label: String, options: List<PickerOption>, selected: String, onSelected: (String) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      options.forEach { option ->
        val isSelected = option.id == selected
        if (isSelected) {
          Button(onClick = { onSelected(option.id) }) { Text(option.label) }
        } else {
          OutlinedButton(onClick = { onSelected(option.id) }, border = manusBorder(alpha = 0.45f)) { Text(option.label) }
        }
      }
    }
  }
}

@Composable
private fun resolveInstanceLabel(modelTierId: String?, identityId: String?): String? {
  if (modelTierId.isNullOrBlank() || identityId.isNullOrBlank()) return null
  val tier =
    when (modelTierId) {
      "m2.5" -> stringResource(R.string.instance_tier_standard_short)
      "glm-4.7" -> stringResource(R.string.instance_tier_pro_short)
      "glm-5" -> stringResource(R.string.instance_tier_max_short)
      else -> return null
    }
  val identity =
    when (identityId) {
      "ecom" -> stringResource(R.string.instance_profile_ecom)
      "docs" -> stringResource(R.string.instance_profile_docs)
      "media" -> stringResource(R.string.instance_profile_media)
      else -> return null
    }
  return "$tier · $identity"
}

@Composable
private fun LanguagePickerRow(selectedTag: String, onSelected: (String) -> Unit) {
  val options =
    listOf(
      "system" to stringResource(R.string.language_system),
      "zh" to stringResource(R.string.language_zh),
      "en" to stringResource(R.string.language_en),
    )
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    options.forEach { (tag, label) ->
      val isSelected = selectedTag == tag
      if (isSelected) {
        Button(onClick = { onSelected(tag) }) { Text(label) }
      } else {
        OutlinedButton(onClick = { onSelected(tag) }, border = manusBorder(alpha = 0.45f)) { Text(label) }
      }
    }
  }
}

@Composable
private fun ToggleChip(
  text: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val shape = RoundedCornerShape(14.dp)
  val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
  val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
  Box(
    modifier =
      modifier
        .clip(shape)
        .background(bg)
        .border(BorderStroke(1.dp, if (selected) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)), shape)
        .clickable { onClick() }
        .padding(vertical = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(text = text, color = fg)
  }
}

@Composable
private fun V2TextField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  modifier: Modifier = Modifier,
  singleLine: Boolean,
  isPassword: Boolean = false,
  keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
  keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
  TextField(
    value = value,
    onValueChange = onValueChange,
    label = { Text(label) },
    modifier = modifier.fillMaxWidth(),
    singleLine = singleLine,
    visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions = keyboardOptions,
    keyboardActions = keyboardActions,
    colors =
      TextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        cursorColor = MaterialTheme.colorScheme.primary,
      ),
    shape = MaterialTheme.shapes.large,
  )
}

@Composable
private fun V2Composer(
  value: String,
  onValueChange: (String) -> Unit,
  onSend: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(16.dp)
        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
        .border(manusBorder(alpha = 0.45f), RoundedCornerShape(24.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    TextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.weight(1f),
      placeholder = { Text(stringResource(R.string.label_message)) },
      colors =
        TextFieldDefaults.colors(
          focusedContainerColor = Color.Transparent,
          unfocusedContainerColor = Color.Transparent,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
          cursorColor = MaterialTheme.colorScheme.primary,
        ),
      singleLine = false,
      maxLines = 6,
    )
    IconButton(
      onClick = onSend,
      enabled = value.isNotBlank(),
      modifier =
        Modifier
          .size(42.dp)
          .clip(CircleShape)
          .background(
            if (value.isNotBlank()) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surface,
          )
          .border(manusBorder(alpha = 0.35f), CircleShape),
    ) {
      Icon(
        imageVector = Icons.Default.Send,
        contentDescription = stringResource(R.string.action_send),
        tint = if (value.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun MessageBubble(message: TestChatMessage, markdown: io.noties.markwon.Markwon) {
  val isOutgoing = message.direction == "out"
  val align = if (isOutgoing) Alignment.End else Alignment.Start
  val bubbleShape = RoundedCornerShape(16.dp)
  val bubbleColor = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
  val textColor = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
  val metaColor = textColor.copy(alpha = 0.7f)
  val metaText =
    if (isOutgoing) {
      val status = v2DeliveryStatusLabel(message.deliveryStatus)
      if (status == null) v2FormatTime(message.timestampMs) else "${v2FormatTime(message.timestampMs)} · $status"
    } else {
      v2FormatTime(message.timestampMs)
    }

  Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
    Box(
      modifier =
        Modifier
          .clip(bubbleShape)
          .background(bubbleColor)
          .then(
            if (isOutgoing) Modifier else Modifier.border(manusBorder(alpha = 0.35f), bubbleShape),
          )
          .padding(horizontal = 14.dp, vertical = 10.dp)
          .widthIn(max = 320.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        V2MarkdownText(markdown = markdown, text = message.text, textColor = textColor)
        Text(
          text = metaText,
          style = MaterialTheme.typography.labelSmall,
          color = metaColor,
          modifier = Modifier.align(Alignment.End),
        )
      }
    }
  }
}

@Composable
private fun v2DeliveryStatusLabel(raw: String?): String? {
  return when (raw) {
    "sending" -> stringResource(R.string.status_sending)
    "sent" -> stringResource(R.string.status_accepted)
    "ack" -> stringResource(R.string.status_replied)
    "failed" -> stringResource(R.string.status_failed)
    else -> null
  }
}
