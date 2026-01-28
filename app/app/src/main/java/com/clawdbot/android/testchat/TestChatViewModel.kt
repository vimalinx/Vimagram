package com.clawdbot.android.testchat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
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
  val threads: List<TestChatThread> = emptyList(),
  val activeChatId: String? = null,
  val messages: List<TestChatMessage> = emptyList(),
)

class TestChatViewModel(app: Application) : AndroidViewModel(app) {
  private val json = Json { ignoreUnknownKeys = true }
  private val client =
    TestServerClient(
      json,
      OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build(),
    )
  private val prefs = TestChatPrefs(app)
  private val store = TestChatStore(json)
  private val notifier = TestChatNotifier(app)

  private val _account = MutableStateFlow(prefs.account.value)
  private val _password = MutableStateFlow(prefs.password.value)
  private val _hosts = MutableStateFlow(prefs.hosts.value)
  private val _connectionState = MutableStateFlow(TestChatConnectionState.Disconnected)
  private val _errorText = MutableStateFlow<String?>(null)
  private val _snapshot = MutableStateFlow(TestChatSnapshot())
  private val _activeChatId = MutableStateFlow<String?>(null)
  private val _isInForeground = MutableStateFlow(true)
  private val _tokenUsage = MutableStateFlow<Map<String, TestChatTokenUsage>>(emptyMap())

  private val hostStates = mutableMapOf<String, TestChatConnectionState>()
  private val hostStreams = mutableMapOf<String, HostStreamState>()
  private var persistJob: Job? = null

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
      UiStateParts(auth, connectionState, errorText, snapshot, activeChatId)
    }

  val uiState: StateFlow<TestChatUiState> =
    combine(baseUiState, _tokenUsage) { base, tokenUsage ->
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
        tokenUsage = tokenUsage,
        sessionUsage = sessionUsage,
        isAuthenticated = isAuthenticated,
        connectionState = base.connectionState,
        errorText = base.errorText,
        threads = sortedThreads,
        activeChatId = base.activeChatId,
        messages = messages,
      )
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, TestChatUiState())

  init {
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
    if (normalizedUser.isBlank() || normalizedInvite.isBlank() || normalizedPassword.length < 6) {
      _errorText.value = "User ID, invite code, and password required"
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
            _errorText.value = "Registration failed: ${it.message}"
            return@launch
          }
      val userId = response.userId?.trim().orEmpty()
      if (response.ok != true || userId.isBlank()) {
        _errorText.value = response.error ?: "Registration failed"
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

  fun loginAccount(serverUrl: String, userId: String, password: String) {
    val normalizedServer = client.normalizeBaseUrl(serverUrl)
    val normalizedUser = userId.trim()
    val normalizedPassword = password.trim()
    if (normalizedUser.isBlank() || normalizedPassword.length < 6) {
      _errorText.value = "User ID and password required"
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
      _errorText.value = "Password missing"
      return
    }
    val normalizedLabel = normalizeHostLabel(label)
    if (normalizedLabel.isBlank()) {
      _errorText.value = "Host name required"
      return
    }
    if (_hosts.value.any { it.label.equals(normalizedLabel, ignoreCase = true) }) {
      _errorText.value = "Host name already exists"
      return
    }
    _errorText.value = null
    viewModelScope.launch {
      val response =
        runCatching { client.requestToken(account.serverUrl, account.userId, password) }
          .getOrElse {
            _errorText.value = "Token request failed: ${it.message}"
            return@launch
          }
      val token = response.token?.trim().orEmpty()
      if (response.ok != true || token.isBlank()) {
        _errorText.value = response.error ?: "Token request failed"
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
        createThread(title = "${host.label} session", hostLabel = host.label, sessionName = "main")
      }
      startStreams(account, nextHosts)
      refreshTokenUsage(account, password)
      onToken(normalizedLabel, token)
    }
  }

  fun logout() {
    stopStreams()
    TestChatForegroundService.stop(getApplication())
    prefs.clearAll()
    _account.value = null
    _password.value = null
    _hosts.value = emptyList()
    _tokenUsage.value = emptyMap()
    _connectionState.value = TestChatConnectionState.Disconnected
    _snapshot.value = TestChatSnapshot()
    _activeChatId.value = null
    _errorText.value = null
  }

  private suspend fun verifyAccountLogin(account: TestChatAccount, password: String): Boolean {
    val response =
      runCatching { client.loginAccount(account.serverUrl, account.userId, password) }
        .getOrElse {
          _errorText.value = "Login failed: ${it.message}"
          _connectionState.value = TestChatConnectionState.Error
          return false
        }
    if (response.ok != true || response.userId.isNullOrBlank()) {
      _errorText.value = response.error ?: "Login failed"
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
            lastMessage = "New chat",
            lastTimestampMs = now,
          )
      snapshot.copy(threads = next)
    }
    openChat(chatId)
  }

  fun backToList() {
    _activeChatId.value = null
  }

  fun createThread(title: String, hostLabel: String, sessionName: String) {
    val normalizedHost = normalizeHostLabel(hostLabel)
    val session = sessionName.trim().ifBlank { "main" }
    val chatId = "machine:${normalizedHost}/${session}"
    updateSnapshot { snapshot ->
      if (snapshot.threads.any { it.chatId == chatId }) return@updateSnapshot snapshot
      val now = System.currentTimeMillis()
      val updated =
        snapshot.threads +
          TestChatThread(
            chatId = chatId,
            title = title.ifBlank { session },
            lastMessage = "Start chatting",
            lastTimestampMs = now,
          )
      snapshot.copy(threads = updated)
    }
  }

  fun sendMessage(text: String) {
    val account = _account.value ?: return
    val chatId = _activeChatId.value ?: return
    if (text.isBlank()) return
    val host = resolveHostForChat(chatId) ?: run {
      _errorText.value = "Host not found for this chat"
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
        text = text.trim(),
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
            _errorText.value = "Send failed: ${it.message}"
            updateMessageStatus(messageId, DELIVERY_FAILED)
            return@launch
          }
      response.use { res ->
        if (!res.isSuccessful) {
          _errorText.value = "Send failed: ${res.code}"
          updateMessageStatus(messageId, DELIVERY_FAILED)
          return@use
        }
      }
      updateMessageStatus(messageId, DELIVERY_SENT)
    }
  }

  private fun appendMessage(message: TestChatMessage, incrementUnread: Boolean) {
    updateSnapshot { snapshot ->
      val nextMessages =
        (snapshot.messages + message).takeLast(MAX_MESSAGES)
      val thread = snapshot.threads.firstOrNull { it.chatId == message.chatId }
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
          )
        }
      val updatedThreads =
        snapshot.threads.filterNot { it.chatId == message.chatId } + updatedThread
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
            val messageId =
              payload?.id?.trim().orEmpty()
                .ifBlank { id?.trim().orEmpty() }
                .ifBlank { UUID.randomUUID().toString() }
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
                senderName = "Bot",
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
            _errorText.value = "${host.label}: ${t?.message ?: "Connection failed"}"
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
    if (hosts.isEmpty()) {
      TestChatForegroundService.stop(getApplication())
      return
    }
    TestChatForegroundService.start(getApplication(), account.userId, hosts.size)
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
    _snapshot.value = snapshot
    ensureDefaultThread(snapshot)
  }

  private fun ensureDefaultThread(snapshot: TestChatSnapshot) {
    if (snapshot.threads.isNotEmpty()) return
    if (_hosts.value.isEmpty()) return
    for (host in _hosts.value) {
      createThread(title = "${host.label} session", hostLabel = host.label, sessionName = "main")
    }
  }

  private fun updateSnapshot(transform: (TestChatSnapshot) -> TestChatSnapshot) {
    val next = transform(_snapshot.value)
    _snapshot.value = next
    schedulePersist()
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
    val identity = parseChatIdentity(chatId)
    return _hosts.value.firstOrNull { it.label.equals(identity.machine, ignoreCase = true) }
  }

  private fun resolveChatIdForHost(hostLabel: String, rawChatId: String): String {
    val trimmed = rawChatId.trim()
    val normalizedHost = normalizeHostLabel(hostLabel)
    if (trimmed.isBlank()) return defaultChatId(normalizedHost)
    if (trimmed.startsWith("machine:") || trimmed.startsWith("device:")) return trimmed
    if (trimmed.contains("/") || trimmed.contains("|")) return trimmed
    if (normalizedHost == "default") return trimmed
    if (trimmed.startsWith("user:") || trimmed.startsWith("test:")) {
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
    val snapshot: TestChatSnapshot,
    val activeChatId: String?,
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

  companion object {
    private const val MAX_MESSAGES = 2000
    private const val DELIVERY_SENDING = "sending"
    private const val DELIVERY_SENT = "sent"
    private const val DELIVERY_ACK = "ack"
    private const val DELIVERY_FAILED = "failed"
  }
}
