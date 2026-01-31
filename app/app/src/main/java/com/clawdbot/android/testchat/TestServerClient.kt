package com.clawdbot.android.testchat

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

@Serializable
data class TestServerSendPayload(
  val userId: String,
  val token: String,
  val text: String,
  val chatId: String,
  val chatType: String,
  val senderName: String? = null,
  val id: String? = null,
)

@Serializable
data class TestServerLoginPayload(
  val token: String,
  val userId: String? = null,
)

@Serializable
data class TestServerTokenPayload(
  val userId: String,
  val password: String,
)

@Serializable
data class TestServerLoginResponse(
  val ok: Boolean? = null,
  val userId: String? = null,
  val token: String? = null,
  val displayName: String? = null,
  val error: String? = null,
)

@Serializable
data class TestServerAccountPayload(
  val userId: String,
  val password: String,
)

@Serializable
data class TestServerTokenResponse(
  val ok: Boolean? = null,
  val userId: String? = null,
  val token: String? = null,
  val error: String? = null,
)

@Serializable
data class TestServerTokenUsageResponse(
  val ok: Boolean? = null,
  val userId: String? = null,
  val usage: List<TestChatTokenUsage>? = null,
  val error: String? = null,
)

@Serializable
data class TestServerHealthResponse(
  val ok: Boolean? = null,
  val error: String? = null,
)

@Serializable
data class TestServerConfigResponse(
  val ok: Boolean? = null,
  val inviteRequired: Boolean? = null,
  val allowRegistration: Boolean? = null,
  val error: String? = null,
)

@Serializable
data class TestServerRegisterPayload(
  val userId: String,
  val inviteCode: String,
  val password: String,
)

@Serializable
data class TestServerStreamPayload(
  val id: String? = null,
  val type: String? = null,
  val chatId: String? = null,
  val text: String? = null,
  val replyToId: String? = null,
  @SerialName("receivedAt")
  val receivedAtMs: Long? = null,
)

class TestServerClient(
  private val json: Json,
  private val client: OkHttpClient,
) {
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  fun openStream(
    credentials: TestChatCredentials,
    lastEventId: Long?,
    listener: EventSourceListener,
  ): EventSource {
    val baseUrl = normalizeBaseUrl(credentials.serverUrl)
    val lastParam =
      if (lastEventId != null && lastEventId > 0) "&lastEventId=${lastEventId}" else ""
    val url = "${baseUrl}/api/stream?userId=${encode(credentials.userId)}${lastParam}"
    val request =
      Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${credentials.token}")
        .header("x-vimalinx-user", credentials.userId)
        .build()
    return EventSources.createFactory(client).newEventSource(request, listener)
  }

  suspend fun loginToken(
    serverUrl: String,
    userId: String?,
    token: String,
  ): TestServerLoginResponse {
    val payload =
      TestServerLoginPayload(
        userId = userId?.trim()?.ifBlank { null },
        token = token.trim(),
      )
    val body = json.encodeToString(TestServerLoginPayload.serializer(), payload)
      .toRequestBody(jsonMediaType)
    val request =
      Request.Builder()
        .url("${normalizeBaseUrl(serverUrl)}/api/login")
        .post(body)
        .build()
    return withContext(Dispatchers.IO) {
      client.newCall(request).execute().use { response ->
        val raw = response.body?.string() ?: ""
        val parsed =
          runCatching { json.decodeFromString(TestServerLoginResponse.serializer(), raw) }
            .getOrNull()
        if (!response.isSuccessful) {
          return@use parsed?.copy(ok = false, error = parsed.error ?: "HTTP ${response.code}")
            ?: TestServerLoginResponse(ok = false, error = "HTTP ${response.code}")
        }
        parsed ?: TestServerLoginResponse(ok = false, error = "Invalid response")
      }
    }
  }

  suspend fun fetchPublicConfig(serverUrl: String): TestServerPublicConfigResponse {
    val request =
      Request.Builder()
        .url("${normalizeBaseUrl(serverUrl)}/api/config")
        .get()
        .build()
    return withContext(Dispatchers.IO) {
      client.newCall(request).execute().use { response ->
        val raw = response.body?.string() ?: ""
        val parsed =
          runCatching {
            json.decodeFromString(TestServerPublicConfigResponse.serializer(), raw)
          }.getOrNull()
        if (!response.isSuccessful) {
          return@use parsed?.copy(ok = false, error = parsed.error ?: "HTTP ${response.code}")
            ?: TestServerPublicConfigResponse(ok = false, error = "HTTP ${response.code}")
        }
        parsed ?: TestServerPublicConfigResponse(ok = false, error = "Invalid response")
      }
    }
  }

  suspend fun registerAccount(
    serverUrl: String,
    userId: String,
    inviteCode: String,
    password: String,
  ): TestServerLoginResponse {
    val payload =
      TestServerRegisterPayload(
        userId = userId.trim(),
        inviteCode = inviteCode.trim(),
        password = password.trim(),
      )
    val body = json.encodeToString(TestServerRegisterPayload.serializer(), payload)
      .toRequestBody(jsonMediaType)
    val request =
      Request.Builder()
        .url("${normalizeBaseUrl(serverUrl)}/api/register")
        .post(body)
        .build()
    return withContext(Dispatchers.IO) {
      client.newCall(request).execute().use { response ->
        val raw = response.body?.string() ?: ""
        val parsed =
          runCatching { json.decodeFromString(TestServerLoginResponse.serializer(), raw) }
            .getOrNull()
        if (!response.isSuccessful) {
          return@use parsed?.copy(ok = false, error = parsed.error ?: "HTTP ${response.code}")
            ?: TestServerLoginResponse(ok = false, error = "HTTP ${response.code}")
        }
        parsed ?: TestServerLoginResponse(ok = false, error = "Invalid response")
      }
    }
  }

  suspend fun loginAccount(
    serverUrl: String,
    userId: String,
    password: String,
  ): TestServerLoginResponse {
    val payload =
      TestServerAccountPayload(
        userId = userId.trim(),
        password = password.trim(),
      )
    val body = json.encodeToString(TestServerAccountPayload.serializer(), payload)
      .toRequestBody(jsonMediaType)
    val request =
      Request.Builder()
        .url("${normalizeBaseUrl(serverUrl)}/api/account/login")
        .post(body)
        .build()
    return withContext(Dispatchers.IO) {
      client.newCall(request).execute().use { response ->
        val raw = response.body?.string() ?: ""
        val parsed =
          runCatching { json.decodeFromString(TestServerLoginResponse.serializer(), raw) }
            .getOrNull()
        if (!response.isSuccessful) {
          return@use parsed?.copy(ok = false, error = parsed.error ?: "HTTP ${response.code}")
            ?: TestServerLoginResponse(ok = false, error = "HTTP ${response.code}")
        }
        parsed ?: TestServerLoginResponse(ok = false, error = "Invalid response")
      }
    }
  }
  suspend fun requestToken(
    serverUrl: String,
    userId: String,
    password: String,
  ): TestServerTokenResponse {
    val payload =
      TestServerTokenPayload(
        userId = userId.trim(),
        password = password.trim(),
      )
    val body = json.encodeToString(TestServerTokenPayload.serializer(), payload)
      .toRequestBody(jsonMediaType)
    val request =
      Request.Builder()
        .url("${normalizeBaseUrl(serverUrl)}/api/token")
        .post(body)
        .build()
    return withContext(Dispatchers.IO) {
      client.newCall(request).execute().use { response ->
        val raw = response.body?.string() ?: ""
        val parsed =
          runCatching { json.decodeFromString(TestServerTokenResponse.serializer(), raw) }
            .getOrNull()
        if (!response.isSuccessful) {
          return@use parsed?.copy(ok = false, error = parsed.error ?: "HTTP ${response.code}")
            ?: TestServerTokenResponse(ok = false, error = "HTTP ${response.code}")
        }
        parsed ?: TestServerTokenResponse(ok = false, error = "Invalid response")
      }
    }
  }

  suspend fun fetchTokenUsage(
    serverUrl: String,
    userId: String,
    password: String,
  ): TestServerTokenUsageResponse {
    val payload =
      TestServerTokenPayload(
        userId = userId.trim(),
        password = password.trim(),
      )
    val body = json.encodeToString(TestServerTokenPayload.serializer(), payload)
      .toRequestBody(jsonMediaType)
    val request =
      Request.Builder()
        .url("${normalizeBaseUrl(serverUrl)}/api/token/usage")
        .post(body)
        .build()
    return withContext(Dispatchers.IO) {
      client.newCall(request).execute().use { response ->
        val raw = response.body?.string() ?: ""
        val parsed =
          runCatching { json.decodeFromString(TestServerTokenUsageResponse.serializer(), raw) }
            .getOrNull()
        if (!response.isSuccessful) {
          return@use parsed?.copy(ok = false, error = parsed.error ?: "HTTP ${response.code}")
            ?: TestServerTokenUsageResponse(ok = false, error = "HTTP ${response.code}")
        }
        parsed ?: TestServerTokenUsageResponse(ok = false, error = "Invalid response")
      }
    }
  }

  suspend fun checkHealth(serverUrl: String): TestServerHealthResponse {
    val request =
      Request.Builder()
        .url("${normalizeBaseUrl(serverUrl)}/healthz")
        .get()
        .build()
    return withContext(Dispatchers.IO) {
      client.newCall(request).execute().use { response ->
        val raw = response.body?.string() ?: ""
        val parsed =
          runCatching { json.decodeFromString(TestServerHealthResponse.serializer(), raw) }
            .getOrNull()
        if (!response.isSuccessful) {
          return@use parsed?.copy(ok = false, error = parsed.error ?: "HTTP ${response.code}")
            ?: TestServerHealthResponse(ok = false, error = "HTTP ${response.code}")
        }
        parsed ?: TestServerHealthResponse(ok = false, error = "Invalid response")
      }
    }
  }

  suspend fun fetchServerConfig(serverUrl: String): TestServerConfigResponse {
    val request =
      Request.Builder()
        .url("${normalizeBaseUrl(serverUrl)}/api/config")
        .get()
        .build()
    return withContext(Dispatchers.IO) {
      client.newCall(request).execute().use { response ->
        val raw = response.body?.string() ?: ""
        val parsed =
          runCatching { json.decodeFromString(TestServerConfigResponse.serializer(), raw) }
            .getOrNull()
        if (!response.isSuccessful) {
          return@use parsed?.copy(ok = false, error = parsed.error ?: "HTTP ${response.code}")
            ?: TestServerConfigResponse(ok = false, error = "HTTP ${response.code}")
        }
        parsed ?: TestServerConfigResponse(ok = false, error = "Invalid response")
      }
    }
  }

  suspend fun sendMessage(
    credentials: TestChatCredentials,
    chatId: String,
    text: String,
    senderName: String?,
    messageId: String?,
  ): Response {
    val payload =
      TestServerSendPayload(
        userId = credentials.userId,
        token = credentials.token,
        text = text,
        chatId = chatId,
        chatType = "dm",
        senderName = senderName,
        id = messageId,
      )
    val body = json.encodeToString(TestServerSendPayload.serializer(), payload)
      .toRequestBody(jsonMediaType)
    val request =
      Request.Builder()
        .url("${normalizeBaseUrl(credentials.serverUrl)}/api/message")
        .post(body)
        .header("Authorization", "Bearer ${credentials.token}")
        .header("x-vimalinx-user", credentials.userId)
        .build()
    return withContext(Dispatchers.IO) { client.newCall(request).execute() }
  }

  fun normalizeBaseUrl(raw: String): String {
    return normalizeServerBaseUrl(raw)
  }

  private fun encode(value: String): String {
    return URLEncoder.encode(value, "UTF-8")
  }
}
