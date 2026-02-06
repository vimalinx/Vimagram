package com.clawdbot.android.testchat

import android.app.Application
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clawdbot.android.BuildConfig
import com.clawdbot.android.R
import com.clawdbot.android.UpdateState
import com.clawdbot.android.UpdateStatus
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

data class TestChatUiState(
  val account: TestChatAccount? = null,
  val hosts: List<TestChatHost> = emptyList(),
  val tokenUsage: Map<String, TestChatTokenUsage> = emptyMap(),
  val sessionUsage: List<TestChatSessionUsage> = emptyList(),
  val isAuthenticated: Boolean = false,
  val connectionState: TestChatConnectionState = TestChatConnectionState.Disconnected,
  val errorText: String? = null,
  val lastConnectionError: String? = null,
  val lastRequestId: String? = null,
  val inviteRequired: Boolean? = null,
  val serverTestMessage: String? = null,
  val serverTestSuccess: Boolean? = null,
  val serverTestInProgress: Boolean = false,
  val threads: List<TestChatThread> = emptyList(),
  val activeChatId: String? = null,
  val messages: List<TestChatMessage> = emptyList(),
)

data class TestServerConfigState(
  val serverUrl: String = "",
  val inviteRequired: Boolean? = null,
  val allowRegistration: Boolean? = null,
  val loading: Boolean = false,
  val error: String? = null,
)

class TestChatViewModel(app: Application) : AndroidViewModel(app) {
  private fun appString(@StringRes id: Int, vararg args: Any): String {
    return getApplication<Application>().getString(id, *args)
  }

  private fun rememberRequestId(requestId: String?) {
    if (!requestId.isNullOrBlank()) {
      _lastRequestId.value = requestId
    }
  }

  private fun formatAuthError(error: String?, requestId: String?, @StringRes fallbackRes: Int): String {
    val normalized = error?.trim().orEmpty()
    val base = when {
      normalized.equals("unauthorized", ignoreCase = true) || normalized.contains("HTTP 401") ->
        appString(R.string.error_unauthorized)
      normalized.contains("HTTP 403") -> appString(R.string.error_forbidden)
      normalized.contains("HTTP 429") || normalized.contains("rate limited", ignoreCase = true) ->
        appString(R.string.error_rate_limited)
      normalized.contains("HTTP 400") -> appString(R.string.error_bad_request)
      normalized.contains("invalid invite", ignoreCase = true) -> appString(R.string.error_invite_invalid)
      normalized.contains("registration disabled", ignoreCase = true) ->
        appString(R.string.error_register_disabled)
      normalized.isNotBlank() -> normalized
      else -> appString(fallbackRes)
    }
    return if (!requestId.isNullOrBlank()) {
      appString(R.string.error_with_request_id, base, requestId)
    } else {
      base
    }
  }
  private val json = Json { ignoreUnknownKeys = true }
  private val client =
    TestServerClient(
      json,
      OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build(),
    )
  private val prefs = TestChatPrefs(app)
  private val store = TestChatStore(json)
  private val notifier = TestChatNotifier(app)

  private val _account = MutableStateFlow(prefs.account.value)
  private val _password = MutableStateFlow(prefs.password.value)
  private val _hosts = MutableStateFlow(prefs.hosts.value)
  private val _languageTag = MutableStateFlow(prefs.languageTag.value)
  private val _disclaimerAccepted = MutableStateFlow(prefs.disclaimerAccepted.value)
  private val _publicChannelEnabled = MutableStateFlow(prefs.publicChannelEnabled.value)
  private val _publicChannelId = MutableStateFlow(prefs.publicChannelId.value)
  private val _publicChannelName = MutableStateFlow(prefs.publicChannelName.value)
  private val _connectionState = MutableStateFlow(TestChatConnectionState.Disconnected)
  private val _errorText = MutableStateFlow<String?>(null)
  private val _lastConnectionError = MutableStateFlow<String?>(null)
  private val _lastRequestId = MutableStateFlow<String?>(null)
  private val _snapshot = MutableStateFlow(TestChatSnapshot())
  private val _activeChatId = MutableStateFlow<String?>(null)
  private val _isInForeground = MutableStateFlow(true)
  private val _tokenUsage = MutableStateFlow<Map<String, TestChatTokenUsage>>(emptyMap())
  private val _inviteRequired = MutableStateFlow<Boolean?>(null)
  private val _serverConfig = MutableStateFlow(TestServerConfigState())
  private val _serverTestMessage = MutableStateFlow<String?>(null)
  private val _serverTestSuccess = MutableStateFlow<Boolean?>(null)
  private val _serverTestInProgress = MutableStateFlow(false)
  private val _updateState = MutableStateFlow(UpdateState())

  private val hostStates = mutableMapOf<String, TestChatConnectionState>()
  private val hostStreams = mutableMapOf<String, HostStreamState>()
  private var persistJob: Job? = null
  private val updateClient = OkHttpClient()

  private val authState =
    combine(_account, _hosts, _password) { account, hosts, password ->
      Triple(account, hosts, password)
    }

  private val baseUiState =
    combine(
      authState,
      _connectionState,
      _errorText,
      _snapshot,
      _activeChatId,
    ) { auth, connectionState, errorText, snapshot, activeChatId ->
      UiStateParts(
        auth = auth,
        connectionState = connectionState,
        errorText = errorText,
        lastConnectionError = null,
        lastRequestId = null,
        snapshot = snapshot,
        activeChatId = activeChatId,
      )
    }

  private val baseUiStateWithErrors =
    combine(
      baseUiState,
      _lastConnectionError,
      _lastRequestId,
    ) { base, lastConnectionError, lastRequestId ->
      base.copy(
        lastConnectionError = lastConnectionError,
        lastRequestId = lastRequestId,
      )
    }

  private val uiStateExtras =
    combine(
      baseUiStateWithErrors,
      _tokenUsage,
      _inviteRequired,
      _serverTestMessage,
      _serverTestSuccess,
    ) { base, tokenUsage, inviteRequired, serverTestMessage, serverTestSuccess ->
      UiStateExtras(
        base = base,
        tokenUsage = tokenUsage,
        inviteRequired = inviteRequired,
        serverTestMessage = serverTestMessage,
        serverTestSuccess = serverTestSuccess,
      )
    }

  val uiState: StateFlow<TestChatUiState> =
    combine(uiStateExtras, _serverTestInProgress) { extras, serverTestInProgress ->
      val base = extras.base
      val (account, hosts, password) = base.auth
      val sortedThreads =
        base.snapshot.threads.sortedByDescending { thread -> thread.lastTimestampMs }
      val sessionUsage = buildSessionUsage(base.snapshot)
      val messages =
        if (base.activeChatId == null) {
          emptyList()
        } else {
          base.snapshot.messages.filter { it.chatId == base.activeChatId }
        }
      val isAuthenticated = account != null && !password.isNullOrBlank()
      TestChatUiState(
        account = account,
        hosts = hosts,
        tokenUsage = extras.tokenUsage,
        sessionUsage = sessionUsage,
        isAuthenticated = isAuthenticated,
        connectionState = base.connectionState,
        errorText = base.errorText,
        lastConnectionError = base.lastConnectionError,
        lastRequestId = base.lastRequestId,
        inviteRequired = extras.inviteRequired,
        serverTestMessage = extras.serverTestMessage,
        serverTestSuccess = extras.serverTestSuccess,
        serverTestInProgress = serverTestInProgress,
        threads = sortedThreads,
        activeChatId = base.activeChatId,
        messages = messages,
      )
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, TestChatUiState())
  val updateState: StateFlow<UpdateState> = _updateState

  val languageTag: StateFlow<String> = _languageTag
  val disclaimerAccepted: StateFlow<Boolean> = _disclaimerAccepted
  val publicChannelEnabled: StateFlow<Boolean> = _publicChannelEnabled
  val publicChannelId: StateFlow<String> = _publicChannelId
  val publicChannelName: StateFlow<String> = _publicChannelName
  val serverConfig: StateFlow<TestServerConfigState> = _serverConfig
  val serverTestMessage: StateFlow<String?> = _serverTestMessage

  init {
    ensurePublicDefaults()
    val account = _account.value
    val password = _password.value
    if (account != null && !password.isNullOrBlank()) {
      viewModelScope.launch {
        val ok = verifyAccountLogin(account, password)
        if (!ok) {
          prefs.saveAccount(account, "")
          _password.value = null
          return@launch
        }
        loadAccount(account)
        startStreams(account, _hosts.value)
        refreshTokenUsage(account, password)
      }
    }
  }

  fun registerAccount(
    serverUrl: String,
    userId: String,
    inviteCode: String,
    password: String,
    onRegistered: (String) -> Unit,
  ) {
    val normalizedServer = client.normalizeBaseUrl(serverUrl)
    val normalizedUser = userId.trim()
    val normalizedInvite = inviteCode.trim()
    val normalizedPassword = password.trim()
    val inviteRequiredFlag = _inviteRequired.value == true
    if (
      normalizedUser.isBlank() ||
        (inviteRequiredFlag && normalizedInvite.isBlank()) ||
        normalizedPassword.length < 6
    ) {
      _errorText.value = appString(R.string.error_register_required)
      return
    }
    val config = _serverConfig.value
    val inviteRequired =
      config.serverUrl == normalizedServer && config.inviteRequired == true
    if (inviteRequired && normalizedInvite.isBlank()) {
      _errorText.value = appString(R.string.error_register_invite_required)
      return
    }
    _errorText.value = null
    viewModelScope.launch {
      val response =
        runCatching {
          client.registerAccount(
            normalizedServer,
            normalizedUser,
            normalizedInvite,
            normalizedPassword,
          )
        }
          .getOrElse {
            _errorText.value =
              appString(R.string.error_register_failed_detail, it.message ?: "")
            return@launch
          }
      val userId = response.userId?.trim().orEmpty()
      if (response.ok != true || userId.isBlank()) {
        rememberRequestId(response.requestId)
        _errorText.value = formatAuthError(response.error, response.requestId, R.string.error_register_failed)
        return@launch
      }
      val account = TestChatAccount(serverUrl = normalizedServer, userId = userId)
      prefs.saveAccount(account, normalizedPassword)
      prefs.saveHosts(emptyList())
      _account.value = account
      _password.value = normalizedPassword
      _hosts.value = emptyList()
      _tokenUsage.value = emptyMap()
      _errorText.value = null
      onRegistered(userId)
      loadAccount(account)
      startStreams(account, _hosts.value)
      refreshTokenUsage(account, normalizedPassword)
    }
  }

  fun refreshServerConfig(serverUrl: String) {
    val normalizedServer = client.normalizeBaseUrl(serverUrl)
    if (_serverConfig.value.serverUrl == normalizedServer && _serverConfig.value.loading) return
    _serverConfig.value =
      _serverConfig.value.copy(
        serverUrl = normalizedServer,
        loading = true,
        error = null,
      )
    viewModelScope.launch {
      val response =
        runCatching { client.fetchPublicConfig(normalizedServer) }
          .getOrElse {
            _serverConfig.value =
              TestServerConfigState(
                serverUrl = normalizedServer,
                inviteRequired = null,
                allowRegistration = null,
                loading = false,
                error = it.message,
              )
            return@launch
          }
      if (response.ok != true) {
        _serverConfig.value =
          TestServerConfigState(
            serverUrl = normalizedServer,
            inviteRequired = null,
            allowRegistration = null,
            loading = false,
            error = response.error,
          )
        return@launch
      }
      _serverConfig.value =
        TestServerConfigState(
          serverUrl = normalizedServer,
          inviteRequired = response.inviteRequired,
          allowRegistration = response.allowRegistration,
          loading = false,
          error = null,
        )
    }
  }

  fun loginAccount(serverUrl: String, userId: String, password: String) {
    val normalizedServer = client.normalizeBaseUrl(serverUrl)
    val normalizedUser = userId.trim()
    val normalizedPassword = password.trim()
    if (normalizedUser.isBlank() || normalizedPassword.length < 6) {
      _errorText.value = appString(R.string.error_login_required)
      return
    }
    _errorText.value = null
    viewModelScope.launch {
      val ok =
        verifyAccountLogin(
          TestChatAccount(normalizedServer, normalizedUser),
          normalizedPassword,
        )
      if (!ok) return@launch
      val account = TestChatAccount(serverUrl = normalizedServer, userId = normalizedUser)
      if (_account.value?.userId != account.userId) {
        prefs.saveHosts(emptyList())
        _hosts.value = emptyList()
        _tokenUsage.value = emptyMap()
      }
      prefs.saveAccount(account, normalizedPassword)
      _account.value = account
      _password.value = normalizedPassword
      _errorText.value = null
      loadAccount(account)
      startStreams(account, _hosts.value)
      refreshTokenUsage(account, normalizedPassword)
    }
  }

  fun generateHostToken(label: String, onToken: (String, String) -> Unit) {
    val account = _account.value ?: return
    val password = _password.value
    if (password.isNullOrBlank()) {
      _errorText.value = appString(R.string.error_password_missing)
      return
    }
    val normalizedLabel = normalizeHostLabel(label)
    if (normalizedLabel.isBlank()) {
      _errorText.value = appString(R.string.error_host_name_required)
      return
    }
    if (_hosts.value.any { it.label.equals(normalizedLabel, ignoreCase = true) }) {
      _errorText.value = appString(R.string.error_host_name_exists)
      return
    }
    _errorText.value = null
    viewModelScope.launch {
      val response =
        runCatching {
          withTimeout(15_000L) {
            client.requestToken(account.serverUrl, account.userId, password)
          }
        }
          .getOrElse {
            _errorText.value =
              if (it is kotlinx.coroutines.TimeoutCancellationException) {
                appString(R.string.error_token_request_timeout)
              } else {
                appString(R.string.error_token_request_failed_detail, it.message ?: "")
              }
            return@launch
          }
      val token = response.token?.trim().orEmpty()
    if (response.ok != true || token.isBlank()) {
        rememberRequestId(response.requestId)
        _errorText.value =
          formatAuthError(response.error, response.requestId, R.string.error_token_request_failed)
        return@launch
      }
      val host = TestChatHost(label = normalizedLabel, token = token)
      val nextHosts = _hosts.value + host
      _hosts.value = nextHosts
      prefs.saveHosts(nextHosts)
      if (_snapshot.value.threads.none {
          parseChatIdentity(it.chatId).machine.equals(host.label, ignoreCase = true)
        }
      ) {
        createThread(
          title = appString(R.string.label_host_session, host.label),
          hostLabel = host.label,
          sessionName = "main",
        )
      }
      startStreams(account, nextHosts)
      refreshTokenUsage(account, password)
      onToken(normalizedLabel, token)
    }
  }

  fun logout() {
    stopStreams()
    TestChatForegroundService.stop(getApplication())
    prefs.clearSession()
    _password.value = null
    _tokenUsage.value = emptyMap()
    _connectionState.value = TestChatConnectionState.Disconnected
    _snapshot.value = TestChatSnapshot()
    _activeChatId.value = null
    _errorText.value = null
    _lastConnectionError.value = null
    _lastRequestId.value = null
  }

  fun setLanguageTag(tag: String) {
    val normalized = tag.trim().ifBlank { "system" }
    if (_languageTag.value == normalized) return
    prefs.saveLanguageTag(normalized)
    _languageTag.value = normalized
  }

  fun acceptDisclaimer() {
    if (_disclaimerAccepted.value) return
    prefs.saveDisclaimerAccepted()
    _disclaimerAccepted.value = true
  }

  fun buildDiagnosticsSummary(): String {
    val account = _account.value
    val serverUrl = account?.serverUrl ?: ""
    val userId = account?.userId ?: ""
    val hostCount = _hosts.value.size
    val publicChannel = if (_publicChannelEnabled.value) _publicChannelId.value else ""
    val publicName = if (_publicChannelEnabled.value) _publicChannelName.value else ""
    val lastError = _lastConnectionError.value ?: _errorText.value ?: ""
    val requestId = _lastRequestId.value ?: ""
    val lines = mutableListOf<String>()
    lines.add("timestamp=${Instant.now()}")
    lines.add("appVersion=${BuildConfig.VERSION_NAME}")
    lines.add("serverUrl=${serverUrl}")
    lines.add("userId=${userId}")
    lines.add("connectionState=${_connectionState.value}")
    lines.add("hosts=${hostCount}")
    if (publicChannel.isNotBlank()) {
      lines.add("publicChannel=${publicChannel}")
      if (publicName.isNotBlank()) {
        lines.add("publicChannelName=${publicName}")
      }
    }
    if (lastError.isNotBlank()) {
      lines.add("lastError=${lastError}")
    }
    if (requestId.isNotBlank()) {
      lines.add("requestId=${requestId}")
    }
    return lines.joinToString("\n")
  }

  fun setPublicChannelEnabled(enabled: Boolean) {
    if (_publicChannelEnabled.value == enabled) return
    prefs.savePublicChannelEnabled(enabled)
    _publicChannelEnabled.value = enabled
    syncPublicChannelState()
  }

  fun setPublicChannelId(channelId: String) {
    val normalized = normalizePublicChannelId(channelId)
    val current = normalizePublicChannelId(_publicChannelId.value)
    if (normalized == current) return
    val previousChatId = resolvePublicChatId(current)
    prefs.savePublicChannelId(normalized)
    _publicChannelId.value = normalized
    if (_publicChannelEnabled.value) {
      syncPublicChannelState(previousChatId)
    }
  }

  fun setPublicChannelName(name: String) {
    val normalized = normalizePublicChannelName(name)
    if (normalized == _publicChannelName.value) return
    prefs.savePublicChannelName(normalized)
    _publicChannelName.value = normalized
    if (_publicChannelEnabled.value) {
      syncPublicChannelState()
    }
  }

  fun checkForUpdates() {
    val currentVersion = resolvedVersionName()
    _updateState.value = UpdateState(status = UpdateStatus.Checking, currentVersion = currentVersion)

    viewModelScope.launch {
      val req =
        Request.Builder()
          .url("https://api.github.com/repos/vimalinx/vimalinx-suite-core/releases/latest")
          .header("User-Agent", buildUserAgent())
          .build()

      val newState =
        try {
          updateClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
              return@use UpdateState(
                status = UpdateStatus.Error,
                currentVersion = currentVersion,
                error = "HTTP ${resp.code}",
              )
            }

            val bodyStr = resp.body?.string()?.trim().orEmpty()
            if (bodyStr.isEmpty()) {
              return@use UpdateState(status = UpdateStatus.Error, currentVersion = currentVersion, error = "empty body")
            }

            val root = json.parseToJsonElement(bodyStr).asObjectOrNull() ?: return@use UpdateState(
              status = UpdateStatus.Error,
              currentVersion = currentVersion,
              error = "invalid json",
            )

            val tagRaw = root["tag_name"].asStringOrNull().orEmpty()
            val name = root["name"].asStringOrNull().orEmpty()
            val body = root["body"].asStringOrNull().orEmpty()
            val htmlUrl = root["html_url"].asStringOrNull().orEmpty()

            val normalizedRemote = normalizeVersion(tagRaw)
            val normalizedCurrent = normalizeVersion(currentVersion)
            val newer = isRemoteNewer(normalizedRemote, normalizedCurrent)

            UpdateState(
              status = UpdateStatus.Ready,
              currentVersion = currentVersion,
              latestTag = tagRaw,
              latestName = name.ifBlank { tagRaw },
              releaseNotes = body,
              htmlUrl = htmlUrl,
              isUpdateAvailable = newer,
            )
          }
        } catch (err: Throwable) {
          UpdateState(status = UpdateStatus.Error, currentVersion = currentVersion, error = err.message ?: "request failed")
        }

      _updateState.value = newState
    }
  }

  fun testServerConnection(serverUrl: String) {
    val normalizedServer = client.normalizeBaseUrl(serverUrl)
    if (normalizedServer.isBlank()) {
      _serverTestMessage.value = appString(R.string.error_server_url_required)
      _serverTestSuccess.value = false
      return
    }
    _serverTestInProgress.value = true
    _serverTestMessage.value = null
    _serverTestSuccess.value = null
    viewModelScope.launch {
      val response =
        runCatching { client.checkHealth(normalizedServer) }
          .getOrElse {
            _serverTestMessage.value =
              appString(R.string.msg_server_test_failed_detail, it.message ?: "")
            _serverTestSuccess.value = false
            _serverTestInProgress.value = false
            return@launch
          }
      if (response.ok == true) {
        _serverTestMessage.value = appString(R.string.msg_server_test_success)
        _serverTestSuccess.value = true
      } else {
        _serverTestMessage.value =
          response.error ?: appString(R.string.msg_server_test_failed)
        _serverTestSuccess.value = false
      }
      _serverTestInProgress.value = false
    }
  }

  fun fetchServerConfig(serverUrl: String) {
    val normalizedServer = client.normalizeBaseUrl(serverUrl)
    if (normalizedServer.isBlank()) {
      _inviteRequired.value = null
      return
    }
    _inviteRequired.value = null
    viewModelScope.launch {
      val response =
        runCatching { client.fetchServerConfig(normalizedServer) }
          .getOrNull()
      _inviteRequired.value = if (response?.ok == true) response.inviteRequired else null
    }
  }

  fun clearServerTestStatus() {
    _serverTestMessage.value = null
    _serverTestSuccess.value = null
    _serverTestInProgress.value = false
  }

  private suspend fun verifyAccountLogin(account: TestChatAccount, password: String): Boolean {
    val response =
      runCatching { client.loginAccount(account.serverUrl, account.userId, password) }
        .getOrElse {
          _errorText.value = appString(R.string.error_login_failed_detail, it.message ?: "")
          _connectionState.value = TestChatConnectionState.Error
          return false
        }
    if (response.ok != true || response.userId.isNullOrBlank()) {
      rememberRequestId(response.requestId)
      _errorText.value = formatAuthError(response.error, response.requestId, R.string.error_login_failed)
      _connectionState.value = TestChatConnectionState.Error
      return false
    }
    _errorText.value = null
    return true
  }

  fun openChat(chatId: String) {
    _activeChatId.value = chatId
    updateSnapshot { snapshot ->
      val updated =
        snapshot.threads.map { thread ->
          if (thread.chatId == chatId) thread.copy(unreadCount = 0) else thread
        }
      snapshot.copy(threads = updated)
    }
  }

  fun openChatFromNotification(chatId: String) {
    if (chatId.isBlank()) return
    if (isPublicChatId(chatId) && !_publicChannelEnabled.value) return
    updateSnapshot { snapshot ->
      if (snapshot.threads.any { it.chatId == chatId }) return@updateSnapshot snapshot
      val now = System.currentTimeMillis()
      val identity = parseChatIdentity(chatId)
      val title = identity.session.ifBlank { chatId }
      val next =
        snapshot.threads +
          TestChatThread(
            chatId = chatId,
            title = title,
            lastMessage = appString(R.string.msg_new_chat),
            lastTimestampMs = now,
          )
      snapshot.copy(threads = next)
    }
    openChat(chatId)
  }

  fun backToList() {
    _activeChatId.value = null
  }

  fun renameThread(chatId: String, title: String) {
    val trimmed = title.trim()
    if (trimmed.isBlank()) return
    updateSnapshot { snapshot ->
      val updated =
        snapshot.threads.map { thread ->
          if (thread.chatId == chatId) thread.copy(title = trimmed) else thread
        }
      snapshot.copy(threads = updated)
    }
  }

  fun togglePinThread(chatId: String) {
    updateSnapshot { snapshot ->
      val updated =
        snapshot.threads.map { thread ->
          if (thread.chatId == chatId) thread.copy(isPinned = !thread.isPinned) else thread
        }
      snapshot.copy(threads = updated)
    }
  }

  fun toggleArchiveThread(chatId: String) {
    updateSnapshot { snapshot ->
      val updated =
        snapshot.threads.map { thread ->
          if (thread.chatId == chatId) {
            thread.copy(isArchived = !thread.isArchived)
          } else {
            thread
          }
        }
      snapshot.copy(threads = updated)
    }
  }

  fun deleteThread(chatId: String) {
    if (_activeChatId.value == chatId) {
      _activeChatId.value = null
    }
    val now = System.currentTimeMillis()
    updateSnapshot { snapshot ->
      val updated =
        snapshot.threads.map { thread ->
          if (thread.chatId == chatId) {
            thread.copy(
              isDeleted = true,
              deletedAt = now,
              isArchived = false,
              isPinned = false,
              unreadCount = 0,
            )
          } else {
            thread
          }
        }
      snapshot.copy(threads = updated)
    }
  }

  fun restoreThread(chatId: String) {
    updateSnapshot { snapshot ->
      val updated =
        snapshot.threads.map { thread ->
          if (thread.chatId == chatId) {
            thread.copy(isDeleted = false, deletedAt = null)
          } else {
            thread
          }
        }
      snapshot.copy(threads = updated)
    }
  }

  fun purgeThread(chatId: String) {
    if (_activeChatId.value == chatId) {
      _activeChatId.value = null
    }
    updateSnapshot { snapshot ->
      val filteredThreads = snapshot.threads.filterNot { it.chatId == chatId }
      val filteredMessages = snapshot.messages.filterNot { it.chatId == chatId }
      snapshot.copy(threads = filteredThreads, messages = filteredMessages)
    }
  }

  fun createThread(title: String, hostLabel: String, sessionName: String) {
    val normalizedHost = normalizeHostLabel(hostLabel)
    val session = sessionName.trim().ifBlank { "main" }
    val chatId = "machine:${normalizedHost}/${session}"
    updateSnapshot { snapshot ->
      val existing = snapshot.threads.firstOrNull { it.chatId == chatId }
      if (existing != null) {
        if (!existing.isDeleted) return@updateSnapshot snapshot
        val now = System.currentTimeMillis()
        val restored =
          existing.copy(
            isDeleted = false,
            deletedAt = null,
            lastTimestampMs = now,
            lastMessage = existing.lastMessage.ifBlank { appString(R.string.msg_start_chatting) },
          )
        val updatedThreads =
          snapshot.threads.map { thread ->
            if (thread.chatId == chatId) restored else thread
          }
        return@updateSnapshot snapshot.copy(threads = updatedThreads)
      }
      val now = System.currentTimeMillis()
      val updated =
        snapshot.threads +
          TestChatThread(
            chatId = chatId,
            title = title.ifBlank { session },
            lastMessage = appString(R.string.msg_start_chatting),
            lastTimestampMs = now,
          )
      snapshot.copy(threads = updated)
    }
  }

  fun createThreadAndOpen(title: String, hostLabel: String, sessionName: String) {
    val normalizedHost = normalizeHostLabel(hostLabel)
    val session = sessionName.trim().ifBlank { "main" }
    val chatId = "machine:${normalizedHost}/${session}"
    createThread(title, hostLabel, sessionName)
    openChat(chatId)
    sendMessage("/new")
  }

  fun sendMessage(text: String) {
    val account = _account.value ?: return
    val chatId = _activeChatId.value ?: return
    val trimmedText = text.trim()
    if (trimmedText.isBlank()) return
    val broadcastText =
      if (isPublicChatId(chatId)) extractBroadcastText(trimmedText) else null
    val messageText = broadcastText ?: trimmedText
    if (messageText.isBlank()) return
    if (broadcastText != null) {
      sendBroadcastMessage(account, chatId, messageText)
      return
    }
    val host = resolveHostForChat(chatId) ?: run {
      _errorText.value = appString(R.string.error_host_not_found)
      return
    }
    _errorText.value = null
    val now = System.currentTimeMillis()
    val messageId = UUID.randomUUID().toString()
    val message =
      TestChatMessage(
        id = messageId,
        chatId = chatId,
        direction = "out",
        text = messageText,
        timestampMs = now,
        senderName = account.userId,
        deliveryStatus = DELIVERY_SENDING,
      )
    appendMessage(message, incrementUnread = false)
    updateLocalTokenUsage(
      token = host.token,
      inboundDelta = 1,
      lastSeenAt = now,
      lastInboundAt = now,
    )
    viewModelScope.launch {
      val credentials =
        TestChatCredentials(
          serverUrl = account.serverUrl,
          userId = account.userId,
          token = host.token,
        )
      val response =
        runCatching {
          client.sendMessage(credentials, chatId, message.text, message.senderName, messageId)
        }
          .getOrElse {
            _errorText.value = appString(R.string.error_send_failed_detail, it.message ?: "")
            updateMessageStatus(messageId, DELIVERY_FAILED)
            return@launch
          }
      response.use { res ->
        if (!res.isSuccessful) {
          _errorText.value = appString(R.string.error_send_failed_code, res.code)
          updateMessageStatus(messageId, DELIVERY_FAILED)
          return@use
        }
      }
      updateMessageStatus(messageId, DELIVERY_SENT)
    }
  }

  private fun sendBroadcastMessage(account: TestChatAccount, chatId: String, text: String) {
    val hosts = _hosts.value
    if (hosts.isEmpty()) {
      _errorText.value = appString(R.string.error_host_not_found)
      return
    }
    _errorText.value = null
    val now = System.currentTimeMillis()
    val messageId = UUID.randomUUID().toString()
    val message =
      TestChatMessage(
        id = messageId,
        chatId = chatId,
        direction = "out",
        text = text,
        timestampMs = now,
        senderName = account.userId,
        deliveryStatus = DELIVERY_SENDING,
      )
    appendMessage(message, incrementUnread = false)
    hosts.forEach { host ->
      updateLocalTokenUsage(
        token = host.token,
        inboundDelta = 1,
        lastSeenAt = now,
        lastInboundAt = now,
      )
    }
    viewModelScope.launch {
      var successCount = 0
      var lastError: String? = null
      for (host in hosts) {
        val credentials =
          TestChatCredentials(
            serverUrl = account.serverUrl,
            userId = account.userId,
            token = host.token,
          )
        val response =
          runCatching {
            client.sendMessage(credentials, chatId, message.text, message.senderName, messageId)
          }
            .getOrElse {
              lastError = it.message
              continue
            }
        response.use { res ->
          if (!res.isSuccessful) {
            lastError = "HTTP ${res.code}"
          } else {
            successCount += 1
          }
        }
      }
      if (successCount == 0) {
        _errorText.value = appString(R.string.error_send_failed_detail, lastError ?: "")
        updateMessageStatus(messageId, DELIVERY_FAILED)
      } else {
        updateMessageStatus(messageId, DELIVERY_SENT)
      }
    }
  }

  private fun extractBroadcastText(raw: String): String? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("@all", ignoreCase = true)) return null
    if (trimmed.length == 4) return ""
    val next = trimmed[4]
    if (!next.isWhitespace()) return null
    return trimmed.drop(4).trim()
  }

  private fun appendMessage(message: TestChatMessage, incrementUnread: Boolean) {
    updateSnapshot { snapshot ->
      val nextMessages =
        (snapshot.messages + message).takeLast(MAX_MESSAGES)
      val thread = snapshot.threads.firstOrNull { it.chatId == message.chatId }
      val updatedThreads =
        if (thread == null && isPublicChatId(message.chatId) && !_publicChannelEnabled.value) {
          snapshot.threads
        } else {
          val updatedThread =
            if (thread == null) {
              TestChatThread(
                chatId = message.chatId,
                title = message.senderName ?: message.chatId,
                lastMessage = message.text,
                lastTimestampMs = message.timestampMs,
                unreadCount = if (incrementUnread) 1 else 0,
              )
            } else {
              thread.copy(
                lastMessage = message.text,
                lastTimestampMs = message.timestampMs,
                unreadCount = if (incrementUnread) thread.unreadCount + 1 else thread.unreadCount,
                isDeleted = false,
                deletedAt = null,
              )
            }
          snapshot.threads.filterNot { it.chatId == message.chatId } + updatedThread
        }
      snapshot.copy(threads = updatedThreads, messages = nextMessages)
    }
  }

  private fun updateMessageStatus(messageId: String, status: String) {
    updateSnapshot { snapshot ->
      val updated =
        snapshot.messages.map { message ->
          if (message.id != messageId) {
            message
          } else {
            val merged = mergeDeliveryStatus(message.deliveryStatus, status)
            if (merged == message.deliveryStatus) message else message.copy(deliveryStatus = merged)
          }
        }
      snapshot.copy(messages = updated)
    }
  }

  private fun acknowledgeLatestOutgoing(chatId: String, replyToId: String?) {
    updateSnapshot { snapshot ->
      val index =
        if (!replyToId.isNullOrBlank()) {
          snapshot.messages.indexOfLast { message ->
            message.chatId == chatId && message.direction == "out" && message.id == replyToId
          }
        } else {
          snapshot.messages.indexOfLast { message ->
            message.chatId == chatId && message.direction == "out"
          }
        }
      if (index == -1) return@updateSnapshot snapshot
      val target = snapshot.messages[index]
      val merged = mergeDeliveryStatus(target.deliveryStatus, DELIVERY_ACK)
      if (merged == target.deliveryStatus) return@updateSnapshot snapshot
      val nextMessages = snapshot.messages.toMutableList()
      nextMessages[index] = target.copy(deliveryStatus = merged)
      snapshot.copy(messages = nextMessages)
    }
  }

  private fun mergeDeliveryStatus(current: String?, next: String): String {
    if (current == DELIVERY_ACK || current == DELIVERY_FAILED) return current
    return next
  }

  private fun startStreams(account: TestChatAccount, hosts: List<TestChatHost>) {
    stopStreams()
    updateForegroundService(account, hosts)
    if (hosts.isEmpty()) {
      _connectionState.value = TestChatConnectionState.Disconnected
      return
    }
    for (host in hosts) {
      openStream(account, host)
    }
  }

  private fun openStream(account: TestChatAccount, host: TestChatHost) {
    hostStreams[host.label]?.stream?.cancel()
    hostStreams[host.label]?.reconnectJob?.cancel()
    hostStates[host.label] = TestChatConnectionState.Connecting
    updateConnectionState()
    val credentials =
      TestChatCredentials(
        serverUrl = account.serverUrl,
        userId = account.userId,
        token = host.token,
      )
    val lastEventId = prefs.getLastEventId(host.label)
    val streamState = HostStreamState(host)
    hostStreams[host.label] = streamState
    streamState.stream =
      client.openStream(
        credentials,
        lastEventId,
        object : EventSourceListener() {
          override fun onOpen(eventSource: EventSource, response: Response) {
            streamState.reconnectAttempts = 0
            hostStates[host.label] = TestChatConnectionState.Connected
            _errorText.value = null
            _lastConnectionError.value = null
            updateConnectionState()
            updateLocalTokenUsage(
              token = host.token,
              streamDelta = 1,
              lastSeenAt = System.currentTimeMillis(),
            )
          }

          override fun onEvent(
            eventSource: EventSource,
            id: String?,
            type: String?,
            data: String,
          ) {
            if (type == "ping" || type == "ready") return
            if (data.isBlank()) return
            val payload =
              runCatching { json.decodeFromString(TestServerStreamPayload.serializer(), data) }
                .getOrNull()
            val text = payload?.text
            if (payload != null && text.isNullOrBlank()) return
            val output = text ?: data
            if (output.isBlank()) return
            val rawMessageId =
              payload?.id?.trim().orEmpty()
                .ifBlank { id?.trim().orEmpty() }
                .ifBlank { UUID.randomUUID().toString() }
            val messageId = "${normalizeHostLabel(host.label)}:${rawMessageId}"
            val eventId = id?.toLongOrNull() ?: payload?.id?.toLongOrNull()
            if (eventId != null) {
              prefs.saveLastEventId(host.label, eventId)
            }
            val rawChatId = payload?.chatId.orEmpty()
            val chatId = resolveChatIdForHost(host.label, rawChatId)
            val timestamp = payload?.receivedAtMs ?: System.currentTimeMillis()
            if (_snapshot.value.messages.any { it.id == messageId }) {
              return
            }
            val message =
              TestChatMessage(
                id = messageId,
                chatId = chatId,
                direction = "in",
                text = output,
                timestampMs = timestamp,
                senderName = appString(R.string.label_bot),
                replyToId = payload?.replyToId,
              )
            acknowledgeLatestOutgoing(chatId, payload?.replyToId)
            val incrementUnread = _activeChatId.value != chatId
            appendMessage(message, incrementUnread = incrementUnread)
            updateLocalTokenUsage(
              token = host.token,
              outboundDelta = 1,
              lastSeenAt = timestamp,
              lastOutboundAt = timestamp,
            )
            val totalUnread = _snapshot.value.threads.sumOf { it.unreadCount }
            val isActive = _activeChatId.value == chatId && _isInForeground.value
            notifier.notifyIncoming(chatId, message, isActive, totalUnread)
          }

          override fun onFailure(
            eventSource: EventSource,
            t: Throwable?,
            response: Response?,
          ) {
            hostStates[host.label] = TestChatConnectionState.Error
            val reason = t?.message ?: appString(R.string.error_connection_failed)
            val message = appString(R.string.error_host_connection_failed, host.label, reason)
            _errorText.value = message
            _lastConnectionError.value = message
            updateConnectionState()
            scheduleReconnect(account, host)
          }
        },
      )
  }

  private fun scheduleReconnect(account: TestChatAccount, host: TestChatHost) {
    val state = hostStreams[host.label] ?: return
    state.reconnectJob?.cancel()
    state.reconnectAttempts += 1
    val delayMs = reconnectDelay(state.reconnectAttempts)
    state.reconnectJob =
      viewModelScope.launch {
        delay(delayMs)
        val currentAccount = _account.value
        if (currentAccount?.userId != account.userId) return@launch
        val currentHosts = _hosts.value
        if (currentHosts.none { it.label == host.label && it.token == host.token }) return@launch
        openStream(account, host)
      }
  }

  private fun reconnectDelay(attempt: Int): Long {
    return listOf(1000L, 2500L, 5000L, 9000L, 15000L)
      .getOrElse(attempt - 1) { 20000L }
  }

  private fun stopStreams() {
    for (state in hostStreams.values) {
      state.reconnectJob?.cancel()
      state.stream?.cancel()
    }
    hostStreams.clear()
    hostStates.clear()
  }

  private fun updateForegroundService(account: TestChatAccount, hosts: List<TestChatHost>) {
    val hostCount = resolveHostCount(hosts)
    if (hostCount == 0) {
      TestChatForegroundService.stop(getApplication())
      return
    }
    TestChatForegroundService.start(getApplication(), account.userId, hostCount)
  }

  private fun resolveHostCount(hosts: List<TestChatHost>): Int {
    return hosts
      .map { it.label.trim() to it.token.trim() }
      .filter { (label, token) -> label.isNotEmpty() && token.isNotEmpty() }
      .distinctBy { (label, _) -> label.lowercase() }
      .size
  }

  private fun updateConnectionState() {
    if (_hosts.value.isEmpty()) {
      _connectionState.value = TestChatConnectionState.Disconnected
      return
    }
    val states = hostStates.values
    _connectionState.value =
      when {
        states.any { it == TestChatConnectionState.Connected } -> TestChatConnectionState.Connected
        states.any { it == TestChatConnectionState.Connecting } -> TestChatConnectionState.Connecting
        states.any { it == TestChatConnectionState.Error } -> TestChatConnectionState.Error
        else -> TestChatConnectionState.Disconnected
      }
  }

  private fun refreshTokenUsage(account: TestChatAccount, password: String) {
    viewModelScope.launch {
      val response =
        runCatching { client.fetchTokenUsage(account.serverUrl, account.userId, password) }
          .getOrElse {
            _tokenUsage.value = emptyMap()
            return@launch
          }
      if (response.ok == true && response.usage != null) {
        _tokenUsage.value = response.usage.associateBy { it.token }
      } else {
        _tokenUsage.value = emptyMap()
      }
    }
  }

  private fun updateLocalTokenUsage(
    token: String,
    inboundDelta: Int = 0,
    outboundDelta: Int = 0,
    streamDelta: Int = 0,
    lastSeenAt: Long? = null,
    lastInboundAt: Long? = null,
    lastOutboundAt: Long? = null,
  ) {
    if (token.isBlank()) return
    val current = _tokenUsage.value[token] ?: TestChatTokenUsage(token = token)
    val next =
      current.copy(
        streamConnects = (current.streamConnects ?: 0) + streamDelta,
        inboundCount = (current.inboundCount ?: 0) + inboundDelta,
        outboundCount = (current.outboundCount ?: 0) + outboundDelta,
        lastSeenAt = lastSeenAt ?: current.lastSeenAt,
        lastInboundAt = lastInboundAt ?: current.lastInboundAt,
        lastOutboundAt = lastOutboundAt ?: current.lastOutboundAt,
      )
    _tokenUsage.value = _tokenUsage.value + (token to next)
  }

  private suspend fun loadAccount(account: TestChatAccount) {
    val snapshot = store.load(getApplication(), account)
    val cleaned = dedupeThreads(snapshot)
    _snapshot.value = cleaned
    if (cleaned.threads.size != snapshot.threads.size) {
      schedulePersist()
    }
    ensureDefaultThread(cleaned)
    syncPublicChannelState()
  }

  private fun ensureDefaultThread(snapshot: TestChatSnapshot) {
    if (snapshot.threads.isNotEmpty()) return
    if (_hosts.value.isEmpty()) return
    for (host in _hosts.value) {
      createThread(
        title = appString(R.string.label_host_session, host.label),
        hostLabel = host.label,
        sessionName = "main",
      )
    }
  }

  private fun ensurePublicDefaults() {
    val normalizedId = normalizePublicChannelId(_publicChannelId.value)
    if (normalizedId != _publicChannelId.value) {
      prefs.savePublicChannelId(normalizedId)
      _publicChannelId.value = normalizedId
    }
    val normalizedName = normalizePublicChannelName(_publicChannelName.value)
    if (normalizedName != _publicChannelName.value) {
      prefs.savePublicChannelName(normalizedName)
      _publicChannelName.value = normalizedName
    }
  }

  private fun syncPublicChannelState(previousChatId: String? = null) {
    val enabled = _publicChannelEnabled.value
    val resolvedChatId = resolvePublicChatId()
    val resolvedTitle = normalizePublicChannelName(_publicChannelName.value)
    if (!enabled) {
      if (_activeChatId.value?.let { isPublicChatId(it) } == true) {
        _activeChatId.value = null
      }
      updateSnapshot { snapshot ->
        val filteredThreads = snapshot.threads.filterNot { isPublicChatId(it.chatId) }
        if (filteredThreads.size == snapshot.threads.size) return@updateSnapshot snapshot
        snapshot.copy(threads = filteredThreads)
      }
      return
    }
    val previous = previousChatId?.ifBlank { null }
    if (_activeChatId.value?.let { isPublicChatId(it) } == true && _activeChatId.value != resolvedChatId) {
      _activeChatId.value = resolvedChatId
    }
    updateSnapshot { snapshot ->
      val hasPublicThread = snapshot.threads.any { isPublicChatId(it.chatId) }
      val updatedMessages =
        if (previous != null && previous != resolvedChatId) {
          snapshot.messages.map { message ->
            if (message.chatId == previous || isPublicChatId(message.chatId)) {
              message.copy(chatId = resolvedChatId)
            } else {
              message
            }
          }
        } else if (hasPublicThread && snapshot.threads.any { isPublicChatId(it.chatId) && it.chatId != resolvedChatId }) {
          snapshot.messages.map { message ->
            if (isPublicChatId(message.chatId)) message.copy(chatId = resolvedChatId) else message
          }
        } else {
          snapshot.messages
        }
      val updatedThreads =
        snapshot.threads.mapNotNull { thread ->
          if (!isPublicChatId(thread.chatId)) return@mapNotNull thread
          if (thread.chatId == resolvedChatId) {
            thread.copy(title = resolvedTitle)
          } else {
            thread.copy(chatId = resolvedChatId, title = resolvedTitle)
          }
        }
      val withPublicThread =
        if (updatedThreads.any { it.chatId == resolvedChatId }) {
          updatedThreads
        } else {
          updatedThreads + buildPublicThread(updatedMessages, resolvedChatId, resolvedTitle)
        }
      snapshot.copy(threads = withPublicThread, messages = updatedMessages)
    }
  }

  private fun buildPublicThread(
    messages: List<TestChatMessage>,
    chatId: String,
    title: String,
  ): TestChatThread {
    val latest = messages.filter { it.chatId == chatId }.maxByOrNull { it.timestampMs }
    val now = System.currentTimeMillis()
    return TestChatThread(
      chatId = chatId,
      title = title,
      lastMessage = latest?.text ?: appString(R.string.msg_start_chatting),
      lastTimestampMs = latest?.timestampMs ?: now,
    )
  }

  private fun resolvePublicChatId(channelId: String = _publicChannelId.value): String {
    val normalized = normalizePublicChannelId(channelId)
    return "${PUBLIC_CHAT_PREFIX}${normalized}"
  }

  private fun normalizePublicChannelId(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return DEFAULT_PUBLIC_CHANNEL_ID
    return trimmed
      .replace(Regex("\\s+"), "-")
      .replace(Regex("[/|:]"), "-")
  }

  private fun normalizePublicChannelName(raw: String): String {
    val trimmed = raw.trim()
    return if (trimmed.isBlank()) {
      appString(R.string.public_channel_default_name)
    } else {
      trimmed
    }
  }

  private fun isPublicChatId(chatId: String): Boolean {
    return chatId.startsWith(PUBLIC_CHAT_PREFIX)
  }

  private fun updateSnapshot(transform: (TestChatSnapshot) -> TestChatSnapshot) {
    val next = dedupeThreads(transform(_snapshot.value))
    _snapshot.value = next
    schedulePersist()
  }

  private fun dedupeThreads(snapshot: TestChatSnapshot): TestChatSnapshot {
    if (snapshot.threads.size <= 1) return snapshot
    val merged = LinkedHashMap<String, TestChatThread>()
    for (thread in snapshot.threads) {
      val existing = merged[thread.chatId]
      if (existing == null) {
        merged[thread.chatId] = thread
        continue
      }
      val latest = if (thread.lastTimestampMs >= existing.lastTimestampMs) thread else existing
      val mergedUnread = maxOf(existing.unreadCount, thread.unreadCount)
      val mergedPinned = existing.isPinned || thread.isPinned
      val mergedArchived = existing.isArchived && thread.isArchived
      val mergedDeleted = existing.isDeleted && thread.isDeleted
      val mergedDeletedAt =
        if (mergedDeleted) {
          listOfNotNull(existing.deletedAt, thread.deletedAt).maxOrNull()
        } else {
          null
        }
      val mergedThread =
        latest.copy(
          title = latest.title.ifBlank { existing.title },
          lastMessage = latest.lastMessage.ifBlank { existing.lastMessage },
          unreadCount = mergedUnread,
          isPinned = mergedPinned,
          isArchived = mergedArchived,
          isDeleted = mergedDeleted,
          deletedAt = mergedDeletedAt,
        )
      merged[thread.chatId] = mergedThread
    }
    return snapshot.copy(threads = merged.values.toList())
  }

  private fun schedulePersist() {
    val account = _account.value ?: return
    persistJob?.cancel()
    persistJob =
      viewModelScope.launch {
        delay(300)
        store.save(getApplication(), account, _snapshot.value)
      }
  }

  private fun resolveHostForChat(chatId: String): TestChatHost? {
    if (isPublicChatId(chatId)) {
      return _hosts.value.firstOrNull()
    }
    val identity = parseChatIdentity(chatId)
    return _hosts.value.firstOrNull { it.label.equals(identity.machine, ignoreCase = true) }
  }

  private fun resolveChatIdForHost(hostLabel: String, rawChatId: String): String {
    val trimmed = rawChatId.trim()
    val normalizedHost = normalizeHostLabel(hostLabel)
    if (trimmed.isBlank()) return defaultChatId(normalizedHost)
    if (trimmed.startsWith(PUBLIC_CHAT_PREFIX)) return trimmed
    if (trimmed.startsWith("machine:") || trimmed.startsWith("device:")) return trimmed
    if (trimmed.contains("/") || trimmed.contains("|")) return trimmed
    if (normalizedHost == "default") return trimmed
    if (trimmed.startsWith("user:") || trimmed.startsWith("vimalinx:")) {
      val session = trimmed.substringAfter(":").ifBlank { "main" }
      return "machine:${normalizedHost}/${session}"
    }
    return "machine:${normalizedHost}/${trimmed}"
  }

  private fun defaultChatId(hostLabel: String): String {
    return "machine:${normalizeHostLabel(hostLabel)}/main"
  }

  private fun normalizeHostLabel(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isBlank()) return "default"
    return trimmed.replace(Regex("[/|:]"), "-")
  }

  override fun onCleared() {
    stopStreams()
    super.onCleared()
  }

  fun setAppInForeground(isForeground: Boolean) {
    _isInForeground.value = isForeground
  }

  private data class HostStreamState(
    val host: TestChatHost,
    var stream: EventSource? = null,
    var reconnectJob: Job? = null,
    var reconnectAttempts: Int = 0,
  )

  private data class UiStateParts(
    val auth: Triple<TestChatAccount?, List<TestChatHost>, String?>,
    val connectionState: TestChatConnectionState,
    val errorText: String?,
    val lastConnectionError: String?,
    val lastRequestId: String?,
    val snapshot: TestChatSnapshot,
    val activeChatId: String?,
  )

  private data class UiStateExtras(
    val base: UiStateParts,
    val tokenUsage: Map<String, TestChatTokenUsage>,
    val inviteRequired: Boolean?,
    val serverTestMessage: String?,
    val serverTestSuccess: Boolean?,
  )

  private data class SessionUsageAccumulator(
    var tokens: Int = 0,
    var lastTimestampMs: Long = 0L,
  )

  private fun buildSessionUsage(snapshot: TestChatSnapshot): List<TestChatSessionUsage> {
    if (snapshot.messages.isEmpty() && snapshot.threads.isEmpty()) return emptyList()
    val threadMap = snapshot.threads.associateBy { it.chatId }
    val usage = mutableMapOf<String, SessionUsageAccumulator>()
    for (message in snapshot.messages) {
      val entry = usage.getOrPut(message.chatId) { SessionUsageAccumulator() }
      entry.tokens += estimateTokens(message.text)
      if (message.timestampMs > entry.lastTimestampMs) {
        entry.lastTimestampMs = message.timestampMs
      }
    }
    for (thread in snapshot.threads) {
      val entry = usage.getOrPut(thread.chatId) { SessionUsageAccumulator() }
      if (thread.lastTimestampMs > entry.lastTimestampMs) {
        entry.lastTimestampMs = thread.lastTimestampMs
      }
    }
    return usage.map { (chatId, entry) ->
      val thread = threadMap[chatId]
      val identity = parseChatIdentity(chatId)
      val sessionLabel = thread?.let { resolveSessionLabel(it) } ?: identity.session
      TestChatSessionUsage(
        chatId = chatId,
        sessionLabel = sessionLabel,
        hostLabel = identity.machine,
        tokenCount = entry.tokens,
        lastTimestampMs = entry.lastTimestampMs,
      )
    }.sortedByDescending { it.lastTimestampMs }
  }

  private fun estimateTokens(text: String): Int {
    if (text.isBlank()) return 0
    var tokens = 0
    var asciiRun = 0
    for (ch in text) {
      if (ch.code <= 0x7f) {
        asciiRun += 1
      } else {
        tokens += (asciiRun + 3) / 4
        asciiRun = 0
        if (!ch.isWhitespace()) {
          tokens += 1
        }
      }
    }
    tokens += (asciiRun + 3) / 4
    return tokens
  }

  private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

  private fun JsonElement?.asStringOrNull(): String? =
    when (this) {
      is JsonNull -> null
      is JsonPrimitive -> content
      else -> null
    }

  private fun resolvedVersionName(): String {
    val versionName = BuildConfig.VERSION_NAME.trim().ifEmpty { "dev" }
    return if (BuildConfig.DEBUG && !versionName.contains("dev", ignoreCase = true)) {
      "${'$'}versionName-dev"
    } else {
      versionName
    }
  }

  private fun buildUserAgent(): String {
    val version = resolvedVersionName()
    val release = Build.VERSION.RELEASE?.trim().orEmpty()
    val releaseLabel = if (release.isEmpty()) "unknown" else release
    return "ClawdbotAndroid/${'$'}version (Android ${'$'}releaseLabel; SDK ${'$'}{Build.VERSION.SDK_INT})"
  }

  private fun normalizeVersion(raw: String?): String {
    return raw?.trim()?.removePrefix("v")?.removePrefix("V")?.trim().orEmpty()
  }

  private fun isRemoteNewer(remote: String, current: String): Boolean {
    if (remote.isBlank()) return false
    if (current.isBlank()) return true

    fun split(v: String): List<String> = v.split('.', '-', '_').filter { it.isNotBlank() }
    val rParts = split(remote)
    val cParts = split(current)
    val max = maxOf(rParts.size, cParts.size)

    for (i in 0 until max) {
      val r = rParts.getOrNull(i).orEmpty()
      val c = cParts.getOrNull(i).orEmpty()
      val rNum = r.toIntOrNull()
      val cNum = c.toIntOrNull()
      if (rNum != null && cNum != null) {
        if (rNum > cNum) return true
        if (rNum < cNum) return false
        continue
      }
      if (r > c) return true
      if (r < c) return false
    }
    return false
  }

  companion object {
    private const val PUBLIC_CHAT_PREFIX = "public:"
    private const val DEFAULT_PUBLIC_CHANNEL_ID = "general"
    private const val MAX_MESSAGES = 2000
    private const val DELIVERY_SENDING = "sending"
    private const val DELIVERY_SENT = "sent"
    private const val DELIVERY_ACK = "ack"
    private const val DELIVERY_FAILED = "failed"
  }
}
