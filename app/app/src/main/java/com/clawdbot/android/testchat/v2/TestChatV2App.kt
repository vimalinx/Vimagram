package com.clawdbot.android.testchat.v2

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.clawdbot.android.AppLocale
import com.clawdbot.android.R
import com.clawdbot.android.UpdateState
import com.clawdbot.android.UpdateStatus
import com.clawdbot.android.testchat.TestChatModeCatalog
import com.clawdbot.android.testchat.TestChatModeOption
import com.clawdbot.android.testchat.TestChatThread
import com.clawdbot.android.testchat.TestChatUiState
import com.clawdbot.android.testchat.TestChatViewModel
import com.clawdbot.android.testchat.TestServerConfigState
import com.clawdbot.android.testchat.TestChatTheme
import java.util.UUID
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestChatV2App(viewModel: TestChatViewModel) {
  val state by viewModel.uiState.collectAsState()
  val languageTag by viewModel.languageTag.collectAsState()
  val disclaimerAccepted by viewModel.disclaimerAccepted.collectAsState()
  val serverConfig by viewModel.serverConfig.collectAsState()
  val updateState by viewModel.updateState.collectAsState()

  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }

  var registrationUserId by remember { mutableStateOf<String?>(null) }
  var currentTab by rememberSaveable { mutableStateOf(MainTab.Chat) }

  val showSnackbar: (String) -> Unit = { msg ->
    scope.launch {
      snackbar.showSnackbar(message = msg, withDismissAction = true, duration = SnackbarDuration.Short)
    }
  }

  LaunchedEffect(languageTag) {
    if (AppLocale.apply(context, languageTag)) {
      (context as? Activity)?.recreate()
    }
  }

  LaunchedEffect(state.isAuthenticated) {
    if (!state.isAuthenticated) currentTab = MainTab.Chat
  }

  TestChatTheme {
    Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      snackbarHost = { SnackbarHost(hostState = snackbar) },
      bottomBar = {
        if (state.activeChatId == null) {
          BottomNav(currentTab = currentTab, onTabSelected = { currentTab = it })
        }
      },
    ) { padding ->
      Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (state.activeChatId != null) {
          ChatScreen(
            state = state,
            selectedModeId = state.selectedModeId,
            modeOptions = state.modeOptions,
            onSelectMode = viewModel::selectMode,
            onBack = viewModel::backToList,
            onSend = { text ->
              viewModel.sendMessage(text)
            },
          )
        } else {
          when (currentTab) {
            MainTab.Chat ->
              ChatListScreen(
                state = state,
                isAuthenticated = state.isAuthenticated,
                selectedModeId = state.selectedModeId,
                modeOptions = state.modeOptions,
                onSelectMode = viewModel::selectMode,
                onOpenChat = viewModel::openChat,
                onNewChat = { title, hostLabel, sessionName, modelTierId, identityId ->
                  viewModel.createInstanceAndOpen(
                    title = title,
                    hostLabel = hostLabel,
                    sessionName = sessionName,
                    modelTierId = modelTierId,
                    identityId = identityId,
                  )
                },
                onGenerateHost = viewModel::generateHostToken,
                onRenameThread = viewModel::renameThread,
                onTogglePinThread = viewModel::togglePinThread,
                onToggleArchiveThread = viewModel::toggleArchiveThread,
                onDeleteThread = viewModel::deleteThread,
                onRestoreThread = viewModel::restoreThread,
                onPurgeThread = viewModel::purgeThread,
                onRequireLogin = { currentTab = MainTab.Account },
                onCopy = { value ->
                  clipboard.setText(AnnotatedString(value))
                  showSnackbar(context.getString(R.string.action_copy_token))
                },
              )

            MainTab.Devices ->
              DevicesScreen(
                state = state,
                isAuthenticated = state.isAuthenticated,
                onRequireLogin = { currentTab = MainTab.Account },
              )

            MainTab.Account ->
              if (state.isAuthenticated) {
                AccountDashboardScreen(
                  state = state,
                  languageTag = languageTag,
                  onLanguageChange = viewModel::setLanguageTag,
                  updateState = updateState,
                  onCheckUpdates = viewModel::checkForUpdates,
                  onLogout = {
                    viewModel.logout()
                    showSnackbar(context.getString(R.string.action_logout))
                  },
                  onCopy = { value ->
                    clipboard.setText(AnnotatedString(value))
                    showSnackbar(context.getString(R.string.action_copy_token))
                  },
                  onOpenUrl = { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                  },
                )
              } else {
                AccountScreen(
                  errorText = state.errorText,
                  deviceId = state.deviceId,
                  selectedModeId = state.selectedModeId,
                  modeOptions = state.modeOptions,
                  inviteRequired = state.inviteRequired,
                  serverTestMessage = state.serverTestMessage,
                  serverTestSuccess = state.serverTestSuccess,
                  serverTestInProgress = state.serverTestInProgress,
                  initialUserId = state.account?.userId,
                  initialServerUrl = state.account?.serverUrl,
                  serverConfig = serverConfig,
                  onSelectMode = viewModel::selectMode,
                  onQuickStart = viewModel::quickStartWithTestAccount,
                  onClearServerTest = viewModel::clearServerTestStatus,
                  onRegister = { serverUrl, userId, inviteCode, password ->
                    viewModel.registerAccount(serverUrl, userId, inviteCode, password) { registeredId ->
                      registrationUserId = registeredId
                    }
                  },
                  onLogin = viewModel::loginAccount,
                  onTestServer = viewModel::testServerConnection,
                  onFetchServerConfig = viewModel::refreshServerConfig,
                )
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
}

private enum class MainTab { Chat, Devices, Account }

@Composable
private fun BottomNav(currentTab: MainTab, onTabSelected: (MainTab) -> Unit) {
  NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
    NavigationBarItem(
      selected = currentTab == MainTab.Chat,
      onClick = { onTabSelected(MainTab.Chat) },
      icon = { Icon(Icons.Default.ChatBubble, contentDescription = stringResource(R.string.tab_chat)) },
      label = { Text(stringResource(R.string.tab_chat)) },
    )
    NavigationBarItem(
      selected = currentTab == MainTab.Devices,
      onClick = { onTabSelected(MainTab.Devices) },
      icon = { Icon(Icons.Default.Devices, contentDescription = stringResource(R.string.tab_devices)) },
      label = { Text(stringResource(R.string.tab_devices)) },
    )
    NavigationBarItem(
      selected = currentTab == MainTab.Account,
      onClick = { onTabSelected(MainTab.Account) },
      icon = { Icon(Icons.Default.Person, contentDescription = stringResource(R.string.tab_account)) },
      label = { Text(stringResource(R.string.tab_account)) },
    )
  }
}
