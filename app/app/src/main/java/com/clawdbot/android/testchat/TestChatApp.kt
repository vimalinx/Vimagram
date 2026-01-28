package com.clawdbot.android.testchat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
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
  var registrationUserId by remember { mutableStateOf<String?>(null) }
  var currentTab by rememberSaveable { mutableStateOf(MainTab.Chat) }
  LaunchedEffect(state.isAuthenticated) {
    if (!state.isAuthenticated) {
      currentTab = MainTab.Chat
    }
  }
  TestChatTheme {
    if (!state.isAuthenticated) {
      AccountScreen(
        errorText = state.errorText,
        initialUserId = state.account?.userId,
        initialServerUrl = state.account?.serverUrl,
        onRegister = { serverUrl, userId, inviteCode, password ->
          viewModel.registerAccount(serverUrl, userId, inviteCode, password) { registeredId ->
            registrationUserId = registeredId
          }
        },
        onLogin = viewModel::loginAccount,
      )
    } else {
      when (currentTab) {
        MainTab.Account -> {
          AccountDashboardScreen(
            state = state,
            currentTab = currentTab,
            onTabSelected = { currentTab = it },
            onLogout = viewModel::logout,
          )
        }
        MainTab.Chat -> {
          if (state.activeChatId == null) {
            ChatListScreen(
              state = state,
              currentTab = currentTab,
              onTabSelected = { currentTab = it },
              onOpenChat = viewModel::openChat,
              onNewChat = viewModel::createThread,
              onGenerateHost = viewModel::generateHostToken,
              onLogout = viewModel::logout,
            )
          } else {
            ChatScreen(
              state = state,
              currentTab = currentTab,
              onTabSelected = { currentTab = it },
              onBack = viewModel::backToList,
              onSend = viewModel::sendMessage,
              onLogout = viewModel::logout,
            )
          }
        }
      }
    }
  }

  if (registrationUserId != null) {
    AlertDialog(
      onDismissRequest = { registrationUserId = null },
      title = { Text("Account created") },
      text = {
        Text(
          text = "Save this user ID for future logins: ${registrationUserId.orEmpty()}",
        )
      },
      confirmButton = {
        TextButton(onClick = { registrationUserId = null }) {
          Text("OK")
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
  initialUserId: String?,
  initialServerUrl: String?,
  onRegister: (serverUrl: String, userId: String, inviteCode: String, password: String) -> Unit,
  onLogin: (serverUrl: String, userId: String, password: String) -> Unit,
) {
  val serverOptions = remember {
    mutableStateListOf(
      ServerOption(label = "vimalinx-server", url = DEFAULT_SERVER_URL),
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

  LaunchedEffect(selectedServer) {
    if (serverOptions.none { it.url == selectedServer } && selectedServer.isNotBlank()) {
      serverOptions.add(
        ServerOption(label = formatServerLabel(selectedServer), url = selectedServer),
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
        text = "Vimagram",
        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = "Register or sign in",
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
          )
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { isLogin = false }) {
              Text("Register")
            }
            TextButton(onClick = { isLogin = true }) {
              Text("Login")
            }
          }
          if (isLogin) {
            TextField(
              value = loginUserId,
              onValueChange = { loginUserId = it },
              label = { Text("User ID") },
              singleLine = true,
              colors = textFieldColors(),
            )
            TextField(
              value = loginPassword,
              onValueChange = { loginPassword = it },
              label = { Text("Password") },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              colors = textFieldColors(),
            )
            Button(
              onClick = { onLogin(selectedServer, loginUserId, loginPassword) },
              modifier = Modifier.fillMaxWidth(),
              enabled = loginUserId.isNotBlank() && loginPassword.isNotBlank(),
            ) {
              Text("Login")
            }
          } else {
            TextField(
              value = registerUserId,
              onValueChange = { registerUserId = it },
              label = { Text("User ID") },
              singleLine = true,
              colors = textFieldColors(),
            )
            TextField(
              value = inviteCode,
              onValueChange = { inviteCode = it },
              label = { Text("Invite code") },
              singleLine = true,
              colors = textFieldColors(),
            )
            TextField(
              value = registerPassword,
              onValueChange = { registerPassword = it },
              label = { Text("Password") },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              colors = textFieldColors(),
            )
            Button(
              onClick = { onRegister(selectedServer, registerUserId, inviteCode, registerPassword) },
              modifier = Modifier.fillMaxWidth(),
              enabled =
                registerUserId.isNotBlank() && inviteCode.isNotBlank() && registerPassword.isNotBlank(),
            ) {
              Text("Register")
            }
          }
        }
      }
    }
  }

  if (showAddServer) {
    AlertDialog(
      onDismissRequest = { showAddServer = false },
      title = { Text("Add server") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          TextField(
            value = newServerName,
            onValueChange = { newServerName = it },
            label = { Text("Server name") },
            singleLine = true,
            colors = textFieldColors(),
          )
          TextField(
            value = newServerUrl,
            onValueChange = { newServerUrl = it },
            label = { Text("Server URL") },
            placeholder = { Text("http://host:8788") },
            singleLine = true,
            colors = textFieldColors(),
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val label = newServerName.trim().ifBlank { "custom-server" }
            val url = normalizeServerUrl(newServerUrl)
            serverOptions.add(ServerOption(label = label, url = url))
            selectedServer = url
            newServerName = ""
            newServerUrl = ""
            showAddServer = false
          },
          enabled = newServerUrl.isNotBlank(),
        ) {
          Text("Add")
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddServer = false }) { Text("Cancel") }
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(
  state: TestChatUiState,
  currentTab: MainTab,
  onTabSelected: (MainTab) -> Unit,
  onOpenChat: (String) -> Unit,
  onNewChat: (String, String, String) -> Unit,
  onGenerateHost: (String, (String, String) -> Unit) -> Unit,
  onLogout: () -> Unit,
) {
  var showNewChat by remember { mutableStateOf(false) }
  var newChatTitle by rememberSaveable { mutableStateOf("") }
  var newChatSession by rememberSaveable { mutableStateOf("") }
  var newChatHost by rememberSaveable { mutableStateOf("") }
  var showAddHost by remember { mutableStateOf(false) }
  var newHostLabel by rememberSaveable { mutableStateOf("") }
  var generatedHost by remember { mutableStateOf<Pair<String, String>?>(null) }
  var searchQuery by rememberSaveable { mutableStateOf("") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = "Vimagram",
              style = MaterialTheme.typography.titleLarge,
            )
            ConnectionStatusRow(state)
          }
        },
        actions = {
          IconButton(onClick = { showAddHost = true }) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add host")
          }
          IconButton(onClick = onLogout) {
            Icon(imageVector = Icons.Default.Logout, contentDescription = "Logout")
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
          Icon(imageVector = Icons.Default.Add, contentDescription = "New chat")
        }
      }
    },
    bottomBar = {
      AppBottomNav(currentTab = currentTab, onTabSelected = onTabSelected)
    },
    bottomBar = {
      AppBottomNav(currentTab = currentTab, onTabSelected = onTabSelected)
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
        InfoCard(text = "No hosts yet. Generate a host token to connect Clawdbot.")
      } else {
          HostListRow(hosts = state.hosts, sessionUsage = state.sessionUsage)
      }
      if (state.threads.isNotEmpty()) {
        TextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          label = { Text("Search sessions") },
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
      LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(filteredThreads) { thread ->
          ChatThreadRow(thread = thread, onClick = { onOpenChat(thread.chatId) })
        }
      }
    }
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
      title = { Text("New chat") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          TextField(
            value = newChatTitle,
            onValueChange = { newChatTitle = it },
            label = { Text("Title") },
            singleLine = true,
            colors = textFieldColors(),
          )
          TextField(
            value = newChatSession,
            onValueChange = { newChatSession = it },
            label = { Text("Session name (optional)") },
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
          Text("Create")
        }
      },
      dismissButton = {
        TextButton(onClick = { showNewChat = false }) { Text("Cancel") }
      },
    )
  }

  if (showAddHost) {
    AlertDialog(
      onDismissRequest = { showAddHost = false },
      title = { Text("Add host") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          TextField(
            value = newHostLabel,
            onValueChange = { newHostLabel = it },
            label = { Text("Host name") },
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
          Text("Generate token")
        }
      },
      dismissButton = {
        TextButton(onClick = { showAddHost = false }) { Text("Cancel") }
      },
    )
  }

  if (generatedHost != null) {
    val info = generatedHost
    val clipboard = LocalClipboardManager.current
    AlertDialog(
      onDismissRequest = { generatedHost = null },
      title = { Text("Host token") },
      text = {
        Text(
          text = "Host: ${info?.first}\nToken: ${info?.second}\nUse this token in Clawdbot.",
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
          Text("Copy token")
        }
      },
      dismissButton = {
        TextButton(onClick = { generatedHost = null }) { Text("OK") }
      },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
  state: TestChatUiState,
  currentTab: MainTab,
  onTabSelected: (MainTab) -> Unit,
  onBack: () -> Unit,
  onSend: (String) -> Unit,
  onLogout: () -> Unit,
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

  BackHandler(enabled = true) {
    onBack()
  }

  LaunchedEffect(state.messages.size) {
    if (state.messages.isNotEmpty()) {
      listState.animateScrollToItem(state.messages.size - 1)
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
            Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(onClick = onLogout) {
            Icon(imageVector = Icons.Default.Logout, contentDescription = "Logout")
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
  currentTab: MainTab,
  onTabSelected: (MainTab) -> Unit,
  onLogout: () -> Unit,
) {
  val account = state.account
  val userLabel = account?.userId ?: "unknown"
  val serverLabel = account?.serverUrl?.let { formatServerLabel(it) } ?: "unknown"
  val sessionUsage = state.sessionUsage
  val totalTokens = sessionUsage.sumOf { it.tokenCount }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Account") },
        actions = {
          IconButton(onClick = onLogout) {
            Icon(imageVector = Icons.Default.Logout, contentDescription = "Logout")
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
        SectionHeader(text = "Account")
        AccountSummaryCard(
          userId = userLabel,
          serverLabel = serverLabel,
        )
      }
      item {
        SectionHeader(text = "Usage totals")
        UsageTotalsCard(
          totalTokens = totalTokens,
          sessionCount = sessionUsage.size,
        )
      }
      item {
        SectionHeader(text = "Sessions")
      }
      if (sessionUsage.isEmpty()) {
        item {
          InfoCard(text = "No session activity yet.")
        }
      } else {
        items(sessionUsage) { usage ->
          SessionUsageCard(usage = usage)
        }
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
      icon = { Icon(imageVector = Icons.Default.ChatBubble, contentDescription = "Chat") },
      label = { Text("Chat") },
    )
    NavigationBarItem(
      selected = currentTab == MainTab.Account,
      onClick = { onTabSelected(MainTab.Account) },
      icon = { Icon(imageVector = Icons.Default.Person, contentDescription = "Account") },
      label = { Text("Account") },
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
        text = "User: $userId",
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = "Server: $serverLabel",
        style = MaterialTheme.typography.bodyMedium,
      )
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
        text = "Total tokens: $totalTokens",
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = "Sessions: $sessionCount",
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun SessionUsageCard(usage: TestChatSessionUsage) {
  val hostColor = resolveMachineColor(usage.hostLabel)
  val lastTime = if (usage.lastTimestampMs > 0) formatTime(usage.lastTimestampMs) else "none"
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
          text = "tokens ${usage.tokenCount}",
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
      TestChatConnectionState.Connected -> "Connected" to Color(0xFF16A34A)
      TestChatConnectionState.Connecting -> "Connecting" to Color(0xFFF59E0B)
      TestChatConnectionState.Error -> "Error" to Color(0xFFDC2626)
      TestChatConnectionState.Disconnected -> "Disconnected" to Color(0xFF64748B)
    }
  val account = state.account
  val serverLabel =
    account?.serverUrl?.let { formatServerLabel(it) } ?: "server not set"
  val userLabel = account?.userId ?: "unknown user"
  val lastActivityMs = state.threads.maxOfOrNull { it.lastTimestampMs }
  val lastActivityLabel = lastActivityMs?.let { formatTime(it) } ?: "none"
  val hostCount = state.hosts.size
  val hostLabel = if (hostCount == 0) "no hosts" else "$hostCount hosts"
  val detail =
    if (!state.errorText.isNullOrBlank()) {
      state.errorText
    } else {
      "$userLabel@$serverLabel · $hostLabel · last $lastActivityLabel"
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
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "Server",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      TextButton(onClick = onAddServer) {
        Text("Add server")
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
        text = "tokens $tokenCount",
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
      text = "No hosts available",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = "Host",
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
private fun ChatThreadRow(thread: TestChatThread, onClick: () -> Unit) {
  val identity = parseChatIdentity(thread.chatId)
  val sessionLabel = resolveSessionLabel(thread)
  val machineLabel = identity.machine
  val machineColor = resolveMachineColor(machineLabel)
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
    }
  }
}

private fun formatServerLabel(raw: String): String {
  val trimmed = raw.trim()
  if (trimmed.isBlank()) return "unknown server"
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

private fun formatDeliveryStatus(raw: String?): String? {
  return when (raw) {
    "sending" -> "Sending"
    "sent" -> "Accepted"
    "ack" -> "Replied"
    "failed" -> "Failed"
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
  val text = if (label.isBlank()) "default" else label
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
      placeholder = { Text("Message") },
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
      Icon(imageVector = Icons.Default.Send, contentDescription = "Send")
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
