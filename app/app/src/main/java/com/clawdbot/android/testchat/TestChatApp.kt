package com.clawdbot.android.testchat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.clawdbot.android.AppLocale
import com.clawdbot.android.R
import com.clawdbot.android.UpdateState
import com.clawdbot.android.UpdateStatus
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TestChatApp(viewModel: TestChatViewModel) {
  val state by viewModel.uiState.collectAsState()
  val languageTag by viewModel.languageTag.collectAsState()
  val disclaimerAccepted by viewModel.disclaimerAccepted.collectAsState()
  val context = LocalContext.current
  var registrationUserId by remember { mutableStateOf<String?>(null) }
  var currentTab by rememberSaveable { mutableStateOf(MainTab.Chat) }
  LaunchedEffect(languageTag) {
    if (AppLocale.apply(context, languageTag)) {
      (context as? Activity)?.recreate()
    }
  }
  LaunchedEffect(state.isAuthenticated) {
    if (!state.isAuthenticated) {
      currentTab = MainTab.Chat
    }
  }
  TestChatTheme {
    if (!state.isAuthenticated) {
      AccountScreen(
        errorText = state.errorText,
        inviteRequired = state.inviteRequired,
        serverTestMessage = state.serverTestMessage,
        serverTestSuccess = state.serverTestSuccess,
        serverTestInProgress = state.serverTestInProgress,
        initialUserId = state.account?.userId,
        initialServerUrl = state.account?.serverUrl,
        onRegister = { serverUrl, userId, inviteCode, password ->
          viewModel.registerAccount(serverUrl, userId, inviteCode, password) { registeredId ->
            registrationUserId = registeredId
          }
        },
        onLogin = viewModel::loginAccount,
        onTestServer = viewModel::testServerConnection,
        onClearServerTest = viewModel::clearServerTestStatus,
        onFetchServerConfig = viewModel::fetchServerConfig,
      )
    } else if (state.activeChatId != null) {
      ChatScreen(
        state = state,
        onBack = viewModel::backToList,
        onSend = viewModel::sendMessage,
      )
    } else {
      Scaffold(
        bottomBar = {
          AppBottomNav(currentTab = currentTab, onTabSelected = { currentTab = it })
        },
        containerColor = MaterialTheme.colorScheme.background,
      ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
          when (currentTab) {
            MainTab.Account -> {
              AccountDashboardScreen(
                state = state,
                onLogout = viewModel::logout,
                languageTag = languageTag,
                onLanguageChange = viewModel::setLanguageTag,
                updateState = viewModel.updateState.collectAsState().value,
                onCheckUpdates = viewModel::checkForUpdates,
              )
            }
            MainTab.Chat -> {
              ChatListScreen(
                state = state,
                onOpenChat = viewModel::openChat,
                onNewChat = viewModel::createThreadAndOpen,
                onGenerateHost = viewModel::generateHostToken,
                onRenameThread = viewModel::renameThread,
                onTogglePinThread = viewModel::togglePinThread,
                onToggleArchiveThread = viewModel::toggleArchiveThread,
                onDeleteThread = viewModel::deleteThread,
                onRestoreThread = viewModel::restoreThread,
                onPurgeThread = viewModel::purgeThread,
              )
            }
          }
        }
      }
    }
  }

  if (registrationUserId != null) {
    AlertDialog(
      onDismissRequest = { registrationUserId = null },
      title = { Text(stringResource(R.string.account_created_title)) },
      text = {
        Text(
          text =
            stringResource(
              R.string.account_created_body,
              registrationUserId.orEmpty(),
            ),
        )
      },
      confirmButton = {
        TextButton(onClick = { registrationUserId = null }) {
          Text(stringResource(R.string.action_ok))
        }
      },
    )
  }

  if (!disclaimerAccepted) {
    AlertDialog(
      onDismissRequest = {},
      title = { Text(stringResource(R.string.disclaimer_title)) },
      text = { Text(stringResource(R.string.disclaimer_body)) },
      confirmButton = {
        TextButton(onClick = { viewModel.acceptDisclaimer() }) {
          Text(stringResource(R.string.action_acknowledge))
        }
      },
      dismissButton = {
        TextButton(onClick = { (context as? Activity)?.finish() }) {
          Text(stringResource(R.string.action_exit))
        }
      },
    )
  }
}

private enum class MainTab {
  Chat,
  Account,
}

@Composable
private fun AccountScreen(
  errorText: String?,
  inviteRequired: Boolean?,
  serverTestMessage: String?,
  serverTestSuccess: Boolean?,
  serverTestInProgress: Boolean,
  initialUserId: String?,
  initialServerUrl: String?,
  onRegister: (serverUrl: String, userId: String, inviteCode: String, password: String) -> Unit,
  onLogin: (serverUrl: String, userId: String, password: String) -> Unit,
  onTestServer: (serverUrl: String) -> Unit,
  onClearServerTest: () -> Unit,
  onFetchServerConfig: (serverUrl: String) -> Unit,
) {
  val serverOptions = remember {
    mutableStateListOf(
      ServerOption(label = "Direct", url = DEFAULT_SERVER_URL),
    )
  }
  val startingServer = initialServerUrl?.trim().orEmpty()
  var selectedServer by rememberSaveable {
    mutableStateOf(if (startingServer.isNotBlank()) startingServer else DEFAULT_SERVER_URL)
  }
  var isLogin by rememberSaveable { mutableStateOf(false) }
  var inviteCode by rememberSaveable { mutableStateOf("") }
  var registerPassword by rememberSaveable { mutableStateOf("") }
  var registerUserId by rememberSaveable { mutableStateOf(initialUserId ?: "") }
  var loginUserId by rememberSaveable { mutableStateOf(initialUserId ?: "") }
  var loginPassword by rememberSaveable { mutableStateOf("") }
  var showAddServer by remember { mutableStateOf(false) }
  var newServerName by rememberSaveable { mutableStateOf("") }
  var newServerUrl by rememberSaveable { mutableStateOf("") }
  val defaultServerLabel = stringResource(R.string.default_custom_server_label)
  val unknownServerLabel = stringResource(R.string.label_unknown_server)

  LaunchedEffect(selectedServer) {
    onClearServerTest()
    onFetchServerConfig(selectedServer)
    if (selectedServer.isNotBlank() && serverOptions.none { it.url == selectedServer }) {
      serverOptions.add(
        ServerOption(
          label = formatServerLabel(selectedServer, unknownServerLabel),
          url = selectedServer,
        ),
      )
    }
  }

  LaunchedEffect(initialUserId) {
    if (!initialUserId.isNullOrBlank() && loginUserId.isBlank()) {
      loginUserId = initialUserId
    }
  }

  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            listOf(Color(0xFFEEF6FF), Color(0xFFF8FAFF), Color(0xFFF1F5F9)),
          ),
        )
        .statusBarsPadding(),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = stringResource(R.string.account_welcome),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (!errorText.isNullOrBlank()) {
            ErrorCard(text = errorText)
          }
          ServerPicker(
            servers = serverOptions,
            selectedUrl = selectedServer,
            onSelected = { selectedServer = it },
            onAddServer = { showAddServer = true },
            onRemoveServer = { url ->
              if (serverOptions.size <= 1) {
                return@ServerPicker
              }
              serverOptions.removeAll { it.url == url }
              if (selectedServer == url) {
                selectedServer = serverOptions.firstOrNull()?.url ?: ""
              }
            }
          )
          if (!serverTestMessage.isNullOrBlank()) {
            if (serverTestSuccess == true) {
              InfoCard(text = serverTestMessage)
            } else {
              ErrorCard(text = serverTestMessage)
            }
          }
          OutlinedButton(
            onClick = { onTestServer(selectedServer) },
            enabled = selectedServer.isNotBlank() && !serverTestInProgress,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              if (serverTestInProgress) {
                stringResource(R.string.status_testing)
              } else {
                stringResource(R.string.action_test_server)
              },
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { isLogin = false }) {
              Text(stringResource(R.string.action_register))
            }
            TextButton(onClick = { isLogin = true }) {
              Text(stringResource(R.string.action_login))
            }
          }
          if (isLogin) {
            TextField(
              value = loginUserId,
              onValueChange = { loginUserId = it },
              label = { Text(stringResource(R.string.label_user_id)) },
              singleLine = true,
              colors = textFieldColors(),
            )
            TextField(
              value = loginPassword,
              onValueChange = { loginPassword = it },
              label = { Text(stringResource(R.string.label_password)) },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              colors = textFieldColors(),
            )
            Button(
              onClick = { onLogin(selectedServer, loginUserId, loginPassword) },
              modifier = Modifier.fillMaxWidth(),
              enabled = loginUserId.isNotBlank() && loginPassword.isNotBlank(),
            ) {
              Text(stringResource(R.string.action_login))
            }
          } else {
            TextField(
              value = registerUserId,
              onValueChange = { registerUserId = it },
              label = { Text(stringResource(R.string.label_user_id)) },
              singleLine = true,
              colors = textFieldColors(),
            )
          val inviteIsRequired = inviteRequired == true
          TextField(
            value = inviteCode,
            onValueChange = { inviteCode = it },
            label = {
              Text(
                stringResource(
                  if (inviteIsRequired) {
                    R.string.label_invite_code
                  } else {
                    R.string.label_invite_code_optional
                  },
                ),
              )
            },
            singleLine = true,
            colors = textFieldColors(),
          )
            TextField(
              value = registerPassword,
              onValueChange = { registerPassword = it },
              label = { Text(stringResource(R.string.label_password)) },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              colors = textFieldColors(),
            )
            Button(
              onClick = { onRegister(selectedServer, registerUserId, inviteCode, registerPassword) },
              modifier = Modifier.fillMaxWidth(),
              enabled =
                registerUserId.isNotBlank() &&
                  registerPassword.isNotBlank() &&
                  (!inviteIsRequired || inviteCode.isNotBlank()),
            ) {
              Text(stringResource(R.string.action_register))
            }
          }
        }
      }
    }
  }

  if (showAddServer) {
    AlertDialog(
      onDismissRequest = { showAddServer = false },
      title = { Text(stringResource(R.string.title_add_server)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          TextField(
            value = newServerName,
            onValueChange = { newServerName = it },
            label = { Text(stringResource(R.string.label_server_name)) },
            singleLine = true,
            colors = textFieldColors(),
          )
          TextField(
            value = newServerUrl,
            onValueChange = { newServerUrl = it },
            label = { Text(stringResource(R.string.label_server_url)) },
            placeholder = { Text(stringResource(R.string.placeholder_server_url)) },
            singleLine = true,
            colors = textFieldColors(),
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val label = newServerName.trim().ifBlank { defaultServerLabel }
            val url = normalizeServerUrl(newServerUrl)
            if (serverOptions.none { it.url == url }) {
              serverOptions.add(ServerOption(label = label, url = url))
            }
            selectedServer = url
            newServerName = ""
            newServerUrl = ""
            showAddServer = false
          },
          enabled = newServerUrl.isNotBlank(),
        ) {
          Text(stringResource(R.string.action_add))
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddServer = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(
  state: TestChatUiState,
  onOpenChat: (String) -> Unit,
  onNewChat: (String, String, String) -> Unit,
  onGenerateHost: (String, (String, String) -> Unit) -> Unit,
  onRenameThread: (String, String) -> Unit,
  onTogglePinThread: (String) -> Unit,
  onToggleArchiveThread: (String) -> Unit,
  onDeleteThread: (String) -> Unit,
  onRestoreThread: (String) -> Unit,
  onPurgeThread: (String) -> Unit,
) {
  var showNewChat by remember { mutableStateOf(false) }
  var newChatTitle by rememberSaveable { mutableStateOf("") }
  var newChatSession by rememberSaveable { mutableStateOf("") }
  var newChatHost by rememberSaveable { mutableStateOf("") }
  var showAddHost by remember { mutableStateOf(false) }
  var newHostLabel by rememberSaveable { mutableStateOf("") }
  var generatedHost by remember { mutableStateOf<Pair<String, String>?>(null) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var renameTarget by remember { mutableStateOf<TestChatThread?>(null) }
  var renameValue by rememberSaveable { mutableStateOf("") }
  var deleteTarget by remember { mutableStateOf<TestChatThread?>(null) }
  var showArchived by rememberSaveable { mutableStateOf(false) }
  var purgeTarget by remember { mutableStateOf<TestChatThread?>(null) }
  var showDeleted by rememberSaveable { mutableStateOf(false) }

  LaunchedEffect(renameTarget?.chatId) {
    renameValue = renameTarget?.let { resolveSessionLabel(it) }.orEmpty()
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.titleLarge,
            )
            ConnectionStatusRow(state)
          }
        },
        actions = {
          IconButton(onClick = { showAddHost = true }) {
            Icon(
              imageVector = Icons.Default.Add,
              contentDescription = stringResource(R.string.action_add_host),
            )
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
          ),
      )
    },
    floatingActionButton = {
      if (state.hosts.isNotEmpty()) {
        FloatingActionButton(onClick = { showNewChat = true }) {
          Icon(
            imageVector = Icons.Default.Add,
            contentDescription = stringResource(R.string.action_new_chat),
          )
        }
      }
    },
    containerColor = MaterialTheme.colorScheme.background,
  ) { padding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(padding)
          .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      AnimatedVisibility(
        visible = state.errorText != null,
        enter = fadeIn(),
        exit = fadeOut(),
      ) {
        ErrorCard(text = state.errorText ?: "")
      }
      if (state.hosts.isEmpty()) {
        InfoCard(text = stringResource(R.string.info_no_hosts))
      } else {
          HostListRow(hosts = state.hosts, sessionUsage = state.sessionUsage)
      }
      if (state.threads.isNotEmpty()) {
        TextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          label = { Text(stringResource(R.string.label_search_sessions)) },
          singleLine = true,
          colors = textFieldColors(),
          modifier = Modifier.fillMaxWidth(),
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
      val activeThreads =
        filteredThreads.filterNot { it.isArchived || it.isDeleted }
      val archivedThreads =
        filteredThreads.filter { it.isArchived && !it.isDeleted }
      val sortedActiveThreads =
        activeThreads.sortedWith(
          compareByDescending<TestChatThread> { it.isPinned }
            .thenByDescending { it.lastTimestampMs },
        )
      val sortedArchivedThreads =
        archivedThreads.sortedWith(
          compareByDescending<TestChatThread> { it.isPinned }
            .thenByDescending { it.lastTimestampMs },
        )
      val sortedDeletedThreads =
        deletedThreads.sortedByDescending { it.deletedAt ?: it.lastTimestampMs }
      LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(sortedActiveThreads) { thread ->
          ChatThreadRow(
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
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.Center,
            ) {
              TextButton(onClick = { showArchived = !showArchived }) {
                Text(
                  text =
                    if (showArchived) {
                      stringResource(
                        R.string.action_hide_archived,
                        sortedArchivedThreads.size,
                      )
                    } else {
                      stringResource(
                        R.string.action_show_archived,
                        sortedArchivedThreads.size,
                      )
                    },
                )
              }
            }
          }
          if (showArchived) {
            items(sortedArchivedThreads) { thread ->
              ChatThreadRow(
                thread = thread,
                onClick = { onOpenChat(thread.chatId) },
                onRename = { renameTarget = thread },
                onTogglePinned = { onTogglePinThread(thread.chatId) },
                onToggleArchived = { onToggleArchiveThread(thread.chatId) },
                onDelete = { deleteTarget = thread },
              )
            }
          }
        }
        if (sortedDeletedThreads.isNotEmpty()) {
          item {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.Center,
            ) {
              TextButton(onClick = { showDeleted = !showDeleted }) {
                Text(
                  text =
                    if (showDeleted) {
                      stringResource(
                        R.string.action_hide_deleted,
                        sortedDeletedThreads.size,
                      )
                    } else {
                      stringResource(
                        R.string.action_show_deleted,
                        sortedDeletedThreads.size,
                      )
                    },
                )
              }
            }
          }
          if (showDeleted) {
            items(sortedDeletedThreads) { thread ->
              DeletedThreadRow(
                thread = thread,
                onRestore = { onRestoreThread(thread.chatId) },
                onDeleteForever = { purgeTarget = thread },
              )
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
        TextField(
          value = renameValue,
          onValueChange = { renameValue = it },
          label = { Text(stringResource(R.string.label_session_name)) },
          singleLine = true,
          colors = textFieldColors(),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            if (target != null) {
              onRenameThread(target.chatId, renameValue)
            }
            renameTarget = null
          },
          enabled = renameValue.isNotBlank(),
        ) {
          Text(stringResource(R.string.action_save))
        }
      },
      dismissButton = {
        TextButton(onClick = { renameTarget = null }) {
          Text(stringResource(R.string.action_cancel))
        }
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
            if (target != null) {
              onDeleteThread(target.chatId)
            }
            deleteTarget = null
          },
        ) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { deleteTarget = null }) {
          Text(stringResource(R.string.action_cancel))
        }
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
            if (target != null) {
              onPurgeThread(target.chatId)
            }
            purgeTarget = null
          },
        ) {
          Text(stringResource(R.string.action_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = { purgeTarget = null }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  if (showNewChat) {
    val hostOptions = state.hosts.map { it.label }
    val fallbackHost = hostOptions.firstOrNull().orEmpty()
    LaunchedEffect(hostOptions) {
      if (newChatHost.isBlank() && fallbackHost.isNotBlank()) {
        newChatHost = fallbackHost
      }
    }
    AlertDialog(
      onDismissRequest = { showNewChat = false },
      title = { Text(stringResource(R.string.title_new_chat)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          TextField(
            value = newChatTitle,
            onValueChange = { newChatTitle = it },
            label = { Text(stringResource(R.string.label_title)) },
            singleLine = true,
            colors = textFieldColors(),
          )
          TextField(
            value = newChatSession,
            onValueChange = { newChatSession = it },
            label = { Text(stringResource(R.string.label_session_name_optional)) },
            singleLine = true,
            colors = textFieldColors(),
          )
          HostPicker(
            hosts = hostOptions,
            selected = newChatHost,
            onSelected = { newChatHost = it },
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val session =
              if (newChatSession.isNotBlank()) newChatSession.trim()
              else "session-${UUID_PREFIX}${System.currentTimeMillis()}"
            onNewChat(newChatTitle, newChatHost, session)
            newChatTitle = ""
            newChatSession = ""
            showNewChat = false
          },
          enabled = newChatHost.isNotBlank(),
        ) {
          Text(stringResource(R.string.action_create))
        }
      },
      dismissButton = {
        TextButton(onClick = { showNewChat = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  if (showAddHost) {
    AlertDialog(
      onDismissRequest = { showAddHost = false },
      title = { Text(stringResource(R.string.title_add_host)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          TextField(
            value = newHostLabel,
            onValueChange = { newHostLabel = it },
            label = { Text(stringResource(R.string.label_host_name)) },
            singleLine = true,
            colors = textFieldColors(),
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            onGenerateHost(newHostLabel) { label, token ->
              generatedHost = label to token
            }
            newHostLabel = ""
            showAddHost = false
          },
          enabled = newHostLabel.isNotBlank(),
        ) {
          Text(stringResource(R.string.action_generate_token))
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddHost = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  if (generatedHost != null) {
    val info = generatedHost
    val clipboard = LocalClipboardManager.current
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
            if (token.isNotBlank()) {
              clipboard.setText(AnnotatedString(token))
            }
          },
        ) {
          Text(stringResource(R.string.action_copy_token))
        }
      },
      dismissButton = {
        TextButton(onClick = { generatedHost = null }) {
          Text(stringResource(R.string.action_ok))
        }
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
  state: TestChatUiState,
  onBack: () -> Unit,
  onSend: (String) -> Unit,
) {
  val chatId = state.activeChatId ?: return
  val thread = state.threads.firstOrNull { it.chatId == chatId }
  val identity = remember(chatId) { parseChatIdentity(chatId) }
  val sessionLabel = thread?.let { resolveSessionLabel(it) } ?: chatId
  val machineLabel = identity.machine
  val machineColor = resolveMachineColor(machineLabel)
  var message by rememberSaveable(chatId) { mutableStateOf("") }
  val listState = rememberLazyListState()
  val messageTextSize = resolveMessageTextSize()
  val markdown = rememberMarkwon(messageTextSize)
  var didInitialScroll by remember(chatId) { mutableStateOf(false) }

  BackHandler(enabled = true) {
    onBack()
  }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
              MachineBadge(label = machineLabel, color = machineColor)
            }
            ConnectionStatusRow(state)
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              imageVector = Icons.Default.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
            )
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
          ),
      )
    },
    containerColor = MaterialTheme.colorScheme.background,
  ) { padding ->
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(padding)
          .imePadding()
          .navigationBarsPadding(),
    ) {
      AnimatedVisibility(
        visible = state.errorText != null,
        enter = fadeIn(),
        exit = fadeOut(),
      ) {
        ErrorCard(text = state.errorText ?: "")
      }
      LazyColumn(
        state = listState,
        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(state.messages) { messageItem ->
          MessageBubble(messageItem, markdown, messageTextSize)
        }
      }

      Composer(
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
private fun AccountDashboardScreen(
  state: TestChatUiState,
  onLogout: () -> Unit,
  languageTag: String,
  onLanguageChange: (String) -> Unit,
  updateState: UpdateState,
  onCheckUpdates: () -> Unit,
) {
  val account = state.account
  val userLabel = account?.userId ?: stringResource(R.string.label_unknown_user)
  val serverLabel =
    account?.serverUrl?.let { formatServerLabel(it, stringResource(R.string.label_unknown_server)) }
      ?: stringResource(R.string.label_unknown_server)
  val hosts = state.hosts
  val clipboard = LocalClipboardManager.current
  val context = LocalContext.current
  var showLogoutConfirm by remember { mutableStateOf(false) }
  var showSettingsSheet by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.title_account)) },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
          ),
      )
    },
    containerColor = MaterialTheme.colorScheme.background,
  ) { padding ->
    LazyColumn(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(padding)
          .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        if (!state.errorText.isNullOrBlank()) {
          ErrorCard(text = state.errorText)
        }
      }
      item {
        SectionHeader(text = stringResource(R.string.title_account_section))
        AccountSummaryCard(
          userId = userLabel,
          serverLabel = serverLabel,
        )
      }
      item {
        SectionHeader(text = stringResource(R.string.title_host_tokens))
        if (hosts.isEmpty()) {
          InfoCard(text = stringResource(R.string.info_no_hosts_connected))
        } else {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            hosts.forEach { host ->
              HostTokenCard(
                host = host,
                onCopy = {
                  clipboard.setText(AnnotatedString(host.token))
                },
              )
            }
          }
        }
      }
      item {
        SectionHeader(text = stringResource(R.string.title_language))
        LanguagePicker(
          selectedTag = languageTag,
          onSelected = onLanguageChange,
        )
      }
      item {
        SectionHeader(text = stringResource(R.string.title_settings))
        Button(
          onClick = { showSettingsSheet = true },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.action_open_settings))
        }
      }
      item {
        Spacer(modifier = Modifier.height(4.dp))
        Button(
          onClick = { showLogoutConfirm = true },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.action_logout))
        }
      }
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
        ) {
          Text(stringResource(R.string.action_logout))
        }
      },
      dismissButton = {
        TextButton(onClick = { showLogoutConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }

  if (showSettingsSheet) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
      onDismissRequest = { showSettingsSheet = false },
      sheetState = sheetState,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        SectionHeader(text = stringResource(R.string.title_updates))
        UpdateSettingsSection(
          updateState = updateState,
          onCheckUpdates = onCheckUpdates,
          onOpenRelease = { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
          },
        )
        SectionHeader(text = stringResource(R.string.title_links))
        LinksSection(
          onOpenLink = { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
          },
        )
      }
    }
  }
}

@Composable
private fun AppBottomNav(
  currentTab: MainTab,
  onTabSelected: (MainTab) -> Unit,
) {
  NavigationBar {
    NavigationBarItem(
      selected = currentTab == MainTab.Chat,
      onClick = { onTabSelected(MainTab.Chat) },
      icon = {
        Icon(
          imageVector = Icons.Default.ChatBubble,
          contentDescription = stringResource(R.string.tab_chat),
        )
      },
      label = { Text(stringResource(R.string.tab_chat)) },
    )
    NavigationBarItem(
      selected = currentTab == MainTab.Account,
      onClick = { onTabSelected(MainTab.Account) },
      icon = {
        Icon(
          imageVector = Icons.Default.Person,
          contentDescription = stringResource(R.string.tab_account),
        )
      },
      label = { Text(stringResource(R.string.tab_account)) },
    )
  }
}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold,
  )
}

@Composable
private fun UpdateSettingsSection(
  updateState: UpdateState,
  onCheckUpdates: () -> Unit,
  onOpenRelease: (String) -> Unit,
) {
  val statusText =
    when (updateState.status) {
      UpdateStatus.Idle -> stringResource(R.string.status_update_idle)
      UpdateStatus.Checking -> stringResource(R.string.status_update_checking)
      UpdateStatus.Ready ->
        if (updateState.isUpdateAvailable) {
          stringResource(R.string.status_update_available)
        } else {
          stringResource(R.string.status_update_uptodate)
        }
      UpdateStatus.Error -> updateState.error ?: stringResource(R.string.status_update_failed)
    }
  InfoCard(text = statusText)
  val checking = updateState.status == UpdateStatus.Checking
  Button(
    onClick = onCheckUpdates,
    enabled = !checking,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      if (checking) stringResource(R.string.action_check_updates_working)
      else stringResource(R.string.action_check_updates),
    )
  }

  if (updateState.status == UpdateStatus.Ready && updateState.isUpdateAvailable) {
    val releaseTitle = updateState.latestName ?: updateState.latestTag ?: stringResource(R.string.label_update_release)
    val notes = updateState.releaseNotes?.trim()?.take(1200).orEmpty()
    val htmlUrl = updateState.htmlUrl.orEmpty()
    Card(
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(text = releaseTitle, style = MaterialTheme.typography.titleSmall)
        if (notes.isNotBlank()) {
          Text(
            text = notes,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 12,
            overflow = TextOverflow.Ellipsis,
          )
        } else {
          Text(
            text = stringResource(R.string.info_update_release_notes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Button(
          onClick = { if (htmlUrl.isNotBlank()) onOpenRelease(htmlUrl) },
          enabled = htmlUrl.isNotBlank(),
        ) {
          Text(stringResource(R.string.action_view_release))
        }
      }
    }
  }
}

@Composable
private fun LinksSection(
  onOpenLink: (String) -> Unit,
) {
  val xhsUrl = "https://xhslink.com/m/487YEE3Jygk"
  val siteUrl = "https://vimalinx.xyz"
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(text = stringResource(R.string.label_xhs), style = MaterialTheme.typography.titleSmall)
      Image(
        painter = painterResource(R.drawable.xhs),
        contentDescription = stringResource(R.string.label_xhs),
        contentScale = ContentScale.Crop,
        modifier =
          Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpenLink(xhsUrl) },
      )
      Text(text = stringResource(R.string.label_website), style = MaterialTheme.typography.titleSmall)
      OutlinedButton(
        onClick = { onOpenLink(siteUrl) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(siteUrl)
      }
    }
  }
}

@Composable
private fun AccountSummaryCard(
  userId: String,
  serverLabel: String,
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = stringResource(R.string.label_user_value, userId),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = stringResource(R.string.label_server_value, serverLabel),
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun HostTokenCard(
  host: TestChatHost,
  onCopy: () -> Unit,
) {
  val color = resolveMachineColor(host.label)
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      MachineBadge(label = host.label, color = color)
      Text(
        text = stringResource(R.string.label_token_value, host.token),
        style = MaterialTheme.typography.bodySmall,
      )
      OutlinedButton(onClick = onCopy) {
        Text(stringResource(R.string.action_copy_token))
      }
    }
  }
}

@Composable
private fun UsageTotalsCard(
  totalTokens: Int,
  sessionCount: Int,
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = stringResource(R.string.label_total_tokens, totalTokens),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = stringResource(R.string.label_sessions_count, sessionCount),
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun SessionUsageCard(usage: TestChatSessionUsage) {
  val hostColor = resolveMachineColor(usage.hostLabel)
  val lastTime =
    if (usage.lastTimestampMs > 0) {
      formatTime(usage.lastTimestampMs)
    } else {
      stringResource(R.string.label_none)
    }
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = usage.sessionLabel,
          style = MaterialTheme.typography.titleMedium,
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
        MachineBadge(label = usage.hostLabel, color = hostColor)
        Text(
          text = stringResource(R.string.label_tokens_short, usage.tokenCount),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun ConnectionStatusRow(state: TestChatUiState) {
  val (label, color) =
    when (state.connectionState) {
      TestChatConnectionState.Connected ->
        stringResource(R.string.status_connected) to Color(0xFF16A34A)
      TestChatConnectionState.Connecting ->
        stringResource(R.string.status_connecting) to Color(0xFFF59E0B)
      TestChatConnectionState.Error ->
        stringResource(R.string.status_error) to Color(0xFFDC2626)
      TestChatConnectionState.Disconnected ->
        stringResource(R.string.status_disconnected) to Color(0xFF64748B)
    }
  val account = state.account
  val serverLabel =
    account?.serverUrl?.let { formatServerLabel(it, stringResource(R.string.label_unknown_server)) }
      ?: stringResource(R.string.label_server_not_set)
  val userLabel = account?.userId ?: stringResource(R.string.label_unknown_user)
  val lastActivityMs = state.threads.maxOfOrNull { it.lastTimestampMs }
  val lastActivityLabel =
    lastActivityMs?.let { formatTime(it) } ?: stringResource(R.string.label_none)
  val hostCount = state.hosts.size
  val hostLabel =
    if (hostCount == 0) {
      stringResource(R.string.label_no_hosts)
    } else {
      stringResource(R.string.label_hosts_count, hostCount)
    }
  val detail =
    if (!state.errorText.isNullOrBlank()) {
      state.errorText
    } else {
      stringResource(
        R.string.label_connection_detail,
        userLabel,
        serverLabel,
        hostLabel,
        lastActivityLabel,
      )
    }
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    Text(
      text = detail,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun ErrorCard(text: String) {
  Card(
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
      ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text = text,
      modifier = Modifier.padding(12.dp),
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun InfoCard(text: String) {
  Card(
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
      ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text = text,
      modifier = Modifier.padding(12.dp),
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

private data class ServerOption(
  val label: String,
  val url: String,
)

@Composable
private fun ServerPicker(
  servers: List<ServerOption>,
  selectedUrl: String,
  onSelected: (String) -> Unit,
  onAddServer: () -> Unit,
  onRemoveServer: (String) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.label_server),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = onAddServer) {
        Text(stringResource(R.string.action_add_server))
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      servers.forEach { server ->
        val isSelected = server.url == selectedUrl
        if (isSelected) {
          Button(onClick = { onSelected(server.url) }) {
            Text(server.label)
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
              imageVector = Icons.Default.Delete,
              contentDescription = "Remove",
              modifier = Modifier.size(16.dp).clickable { onRemoveServer(server.url) },
            )
          }
        } else {
          OutlinedButton(onClick = { onSelected(server.url) }) {
            Text(server.label)
          }
        }
      }
    }
  }
}

@Composable
private fun HostListRow(hosts: List<TestChatHost>, sessionUsage: List<TestChatSessionUsage>) {
  if (hosts.isEmpty()) return
  val usageByHost =
    sessionUsage.groupBy { it.hostLabel }
      .mapValues { entry -> entry.value.sumOf { it.tokenCount } }
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    hosts.forEach { host ->
      val tokens = usageByHost[host.label] ?: 0
      HostUsageCard(host = host, tokenCount = tokens)
    }
  }
}

@Composable
private fun HostUsageCard(host: TestChatHost, tokenCount: Int) {
  val color = resolveMachineColor(host.label)
  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      MachineBadge(label = host.label, color = color)
      Text(
        text = stringResource(R.string.label_tokens_short, tokenCount),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostPicker(
  hosts: List<String>,
  selected: String,
  onSelected: (String) -> Unit,
) {
  if (hosts.isEmpty()) {
    Text(
      text = stringResource(R.string.info_no_hosts_available),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = stringResource(R.string.label_host),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      hosts.forEach { host ->
        val isSelected = host == selected
        if (isSelected) {
          Button(onClick = { onSelected(host) }) {
            Text(host)
          }
        } else {
          OutlinedButton(onClick = { onSelected(host) }) {
            Text(host)
          }
        }
      }
    }
  }
}

@Composable
private fun LanguagePicker(
  selectedTag: String,
  onSelected: (String) -> Unit,
) {
  val options =
    listOf(
      Pair("system", stringResource(R.string.language_system)),
      Pair("zh", stringResource(R.string.language_zh)),
      Pair("en", stringResource(R.string.language_en)),
    )
  Row(
    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    options.forEach { (tag, label) ->
      val isSelected = selectedTag == tag
      if (isSelected) {
        Button(onClick = { onSelected(tag) }) {
          Text(label)
        }
      } else {
        OutlinedButton(onClick = { onSelected(tag) }) {
          Text(label)
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
  val machineColor = resolveMachineColor(machineLabel)
  val deletedAt = thread.deletedAt ?: thread.lastTimestampMs
  Card(
    shape = RoundedCornerShape(18.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface,
      ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        ChatAvatar(label = sessionLabel, color = machineColor)
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
              text = formatTime(deletedAt),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Spacer(modifier = Modifier.height(4.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            MachineBadge(label = machineLabel, color = machineColor)
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
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRestore) {
          Text(stringResource(R.string.action_restore))
        }
        OutlinedButton(onClick = onDeleteForever) {
          Text(stringResource(R.string.action_delete_forever))
        }
      }
    }
  }
}

@Composable
private fun ChatThreadRow(
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
  val machineColor = resolveMachineColor(machineLabel)
  var menuExpanded by remember { mutableStateOf(false) }
  Card(
    shape = RoundedCornerShape(18.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface,
      ),
    modifier = Modifier.clickable { onClick() },
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      ChatAvatar(label = sessionLabel, color = machineColor)
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
            text = formatTime(thread.lastTimestampMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          MachineBadge(label = machineLabel, color = machineColor)
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
            UnreadBadge(count = thread.unreadCount)
          }
        }
      }
      Box {
        IconButton(onClick = { menuExpanded = true }) {
          Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.action_more),
          )
        }
        DropdownMenu(
          expanded = menuExpanded,
          onDismissRequest = { menuExpanded = false },
        ) {
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
              Text(
                if (thread.isPinned) {
                  stringResource(R.string.action_unpin)
                } else {
                  stringResource(R.string.action_pin)
                },
              )
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
                if (thread.isArchived) {
                  stringResource(R.string.action_unarchive)
                } else {
                  stringResource(R.string.action_archive)
                },
              )
            },
            onClick = {
              menuExpanded = false
              onToggleArchived()
            },
            leadingIcon = {
              Icon(
                imageVector =
                  if (thread.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
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

private fun formatServerLabel(raw: String, fallback: String): String {
  val trimmed = raw.trim()
  if (trimmed.isBlank()) return fallback
  return runCatching {
    val uri = URI(trimmed)
    val host = uri.host ?: trimmed.removePrefix("http://").removePrefix("https://")
    val port = if (uri.port > 0) ":${uri.port}" else ""
    "${host}${port}".trimEnd('/')
  }.getOrElse { trimmed }
}

private fun normalizeServerUrl(raw: String): String {
  val trimmed = raw.trim().removeSuffix("/")
  if (trimmed.isBlank()) return DEFAULT_SERVER_URL
  return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
    trimmed
  } else {
    "http://$trimmed"
  }
}

@Composable
private fun formatDeliveryStatus(raw: String?): String? {
  return when (raw) {
    "sending" -> stringResource(R.string.status_sending)
    "sent" -> stringResource(R.string.status_accepted)
    "ack" -> stringResource(R.string.status_replied)
    "failed" -> stringResource(R.string.status_failed)
    else -> null
  }
}

private fun resolveMachineColor(label: String): Color {
  if (label.isBlank()) return machinePalette.first()
  val index = kotlin.math.abs(label.lowercase().hashCode()) % machinePalette.size
  return machinePalette[index]
}

@Composable
private fun ChatAvatar(label: String, color: Color) {
  val initial = label.trim().take(1).uppercase()
  Box(
    modifier =
      Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(color.copy(alpha = 0.18f)),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = initial,
      style = MaterialTheme.typography.titleMedium,
      color = color,
    )
  }
}

@Composable
private fun MachineBadge(label: String, color: Color) {
  val text = if (label.isBlank()) stringResource(R.string.label_default_machine) else label
  Box(
    modifier =
      Modifier
        .clip(RoundedCornerShape(10.dp))
        .background(color.copy(alpha = 0.18f))
        .padding(horizontal = 8.dp, vertical = 2.dp),
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
private fun UnreadBadge(count: Int) {
  Box(
    modifier =
      Modifier
        .wrapContentWidth()
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary)
        .padding(horizontal = 8.dp, vertical = 2.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = count.toString(),
      style = MaterialTheme.typography.labelSmall.copy(color = Color.White),
    )
  }
}

@Composable
private fun MessageBubble(
  message: TestChatMessage,
  markdown: Markwon,
  messageTextSize: TextUnit,
) {
  val isOutgoing = message.direction == "out"
  val alignment = if (isOutgoing) Alignment.End else Alignment.Start
  val bubbleColor =
    if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
  val textColor =
    if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
  val statusLabel =
    if (isOutgoing) {
      formatDeliveryStatus(message.deliveryStatus)
    } else {
      null
    }
  val metaLabel =
    if (statusLabel == null) formatTime(message.timestampMs)
    else "${formatTime(message.timestampMs)} · $statusLabel"

  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = alignment,
  ) {
    Box(
      modifier =
        Modifier
          .clip(RoundedCornerShape(18.dp))
        .background(bubbleColor)
        .padding(horizontal = 14.dp, vertical = 10.dp)
        .widthIn(max = 280.dp),
    ) {
      Column {
        MarkdownText(
          markdown = markdown,
          text = message.text,
          textColor = textColor,
          fontSize = messageTextSize,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = metaLabel,
          style = MaterialTheme.typography.labelSmall,
          color = textColor.copy(alpha = 0.7f),
          modifier = Modifier.align(Alignment.End),
        )
      }
    }
  }
}

@Composable
private fun MarkdownText(
  markdown: Markwon,
  text: String,
  textColor: Color,
  fontSize: TextUnit,
) {
  val context = LocalContext.current
  AndroidView(
    factory = {
      TextView(context).apply {
        setTextColor(textColor.toArgb())
        setLinkTextColor(textColor.toArgb())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.value)
        setLineSpacing(0f, 1.15f)
        setBackgroundColor(Color.Transparent.toArgb())
        movementMethod = LinkMovementMethod.getInstance()
      }
    },
    update = { view ->
      view.setTextColor(textColor.toArgb())
      view.setLinkTextColor(textColor.toArgb())
      view.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.value)
      markdown.setMarkdown(view, text)
    },
  )
}

@Composable
private fun rememberMarkwon(fontSize: TextUnit): Markwon {
  val context = LocalContext.current
  val fontSizePx = with(LocalDensity.current) { fontSize.toPx() }
  return remember(fontSizePx) {
    Markwon.builder(context)
      .usePlugin(LinkifyPlugin.create())
      .usePlugin(JLatexMathPlugin.create(fontSizePx))
      .usePlugin(TablePlugin.create(context))
      .build()
  }
}

@Composable
private fun resolveMessageTextSize(): TextUnit {
  val style = MaterialTheme.typography.bodyMedium
  return if (style.fontSize.isUnspecified) {
    16.sp
  } else {
    style.fontSize
  }
}

@Composable
private fun Composer(
  value: String,
  onValueChange: (String) -> Unit,
  onSend: () -> Unit,
) {
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(16.dp)
        .background(
          MaterialTheme.colorScheme.surface,
          RoundedCornerShape(22.dp),
        )
        .padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    TextField(
      value = value,
      onValueChange = onValueChange,
      modifier = Modifier.weight(1f),
      placeholder = { Text(stringResource(R.string.label_message)) },
      maxLines = 4,
      colors =
        TextFieldDefaults.colors(
          focusedContainerColor = Color.Transparent,
          unfocusedContainerColor = Color.Transparent,
          disabledContainerColor = Color.Transparent,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent,
        ),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
      keyboardActions =
        KeyboardActions(
          onSend = { onSend() },
        ),
    )
    IconButton(
      onClick = onSend,
      enabled = value.isNotBlank(),
    ) {
      Icon(
        imageVector = Icons.Default.Send,
        contentDescription = stringResource(R.string.action_send),
      )
    }
  }
}

@Composable
private fun textFieldColors() =
  TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
  )

private val timeFormatter: DateTimeFormatter =
  DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun formatTime(timestampMs: Long): String {
  return timeFormatter.format(Instant.ofEpochMilli(timestampMs))
}

private val machinePalette =
  listOf(
    Color(0xFF2563EB),
    Color(0xFF16A34A),
    Color(0xFFEA580C),
    Color(0xFFDC2626),
    Color(0xFF7C3AED),
    Color(0xFF0F766E),
    Color(0xFF9333EA),
  )

private const val UUID_PREFIX = "local"
private const val DEFAULT_SERVER_URL = "http://123.60.21.129:8788"
