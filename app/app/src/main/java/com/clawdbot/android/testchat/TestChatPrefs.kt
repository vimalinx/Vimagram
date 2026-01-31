package com.clawdbot.android.testchat

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class TestChatPrefs(context: Context) {
  companion object {
    private const val prefsName = "clawdbot.testchat.secure"
    private const val serverUrlKey = "testchat.serverUrl"
    private const val userIdKey = "testchat.userId"
    private const val passwordKey = "testchat.password"
    private const val hostsKey = "testchat.hosts"
    private const val legacyTokenKey = "testchat.token"
    private const val lastEventIdsKey = "testchat.lastEventIds"
    private const val languageKey = "testchat.language"
    private const val disclaimerKey = "testchat.disclaimerAccepted"
  }

  private val json = Json { ignoreUnknownKeys = true }

  private val masterKey =
    MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()

  private val prefs =
    EncryptedSharedPreferences.create(
      context,
      prefsName,
      masterKey,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

  private val _account = MutableStateFlow(loadAccount())
  val account: StateFlow<TestChatAccount?> = _account

  private val _password = MutableStateFlow(loadPassword())
  val password: StateFlow<String?> = _password

  private val _hosts = MutableStateFlow(loadHosts())
  val hosts: StateFlow<List<TestChatHost>> = _hosts

  private val _languageTag = MutableStateFlow(loadLanguageTag())
  val languageTag: StateFlow<String> = _languageTag

  private val _disclaimerAccepted = MutableStateFlow(loadDisclaimerAccepted())
  val disclaimerAccepted: StateFlow<Boolean> = _disclaimerAccepted

  private val lastEventIds = loadLastEventIds().toMutableMap()

  fun saveAccount(account: TestChatAccount, password: String) {
    val normalizedServer = normalizeServerBaseUrl(account.serverUrl)
    val sanitized =
      TestChatAccount(
        serverUrl = normalizedServer.trim(),
        userId = account.userId.trim(),
      )
    prefs.edit {
      putString(serverUrlKey, sanitized.serverUrl)
      putString(userIdKey, sanitized.userId)
      putString(passwordKey, password.trim())
      remove(legacyTokenKey)
    }
    _account.value = sanitized
    _password.value = password.trim()
  }

  fun saveHosts(hosts: List<TestChatHost>) {
    val sanitized =
      hosts.map {
        TestChatHost(label = it.label.trim(), token = it.token.trim())
      }
    prefs.edit {
      putString(hostsKey, json.encodeToString(ListSerializer(TestChatHost.serializer()), sanitized))
    }
    _hosts.value = sanitized
  }

  fun clearAll() {
    prefs.edit {
      remove(serverUrlKey)
      remove(userIdKey)
      remove(passwordKey)
      remove(hostsKey)
      remove(legacyTokenKey)
      remove(lastEventIdsKey)
    }
    _account.value = null
    _password.value = null
    _hosts.value = emptyList()
    lastEventIds.clear()
  }

  fun clearSession() {
    prefs.edit {
      remove(passwordKey)
    }
    _password.value = null
  }

  fun saveLanguageTag(tag: String) {
    val normalized = tag.trim().ifBlank { "system" }
    prefs.edit {
      putString(languageKey, normalized)
    }
    _languageTag.value = normalized
  }

  fun saveDisclaimerAccepted() {
    prefs.edit {
      putBoolean(disclaimerKey, true)
    }
    _disclaimerAccepted.value = true
  }

  fun getLastEventId(hostLabel: String): Long? {
    return lastEventIds[hostLabel.trim()]
  }

  fun saveLastEventId(hostLabel: String, eventId: Long) {
    val key = hostLabel.trim()
    if (key.isBlank()) return
    lastEventIds[key] = eventId
    prefs.edit {
      putString(
        lastEventIdsKey,
        json.encodeToString(
          MapSerializer(String.serializer(), Long.serializer()),
          lastEventIds,
        ),
      )
    }
  }

  private fun loadAccount(): TestChatAccount? {
    val rawServerUrl = prefs.getString(serverUrlKey, null)?.trim().orEmpty()
    val serverUrl = normalizeServerBaseUrl(rawServerUrl)
    val userId = prefs.getString(userIdKey, null)?.trim().orEmpty()
    if (serverUrl.isNotBlank() && serverUrl != rawServerUrl) {
      prefs.edit { putString(serverUrlKey, serverUrl) }
    }
    return if (serverUrl.isNotEmpty() && userId.isNotEmpty()) {
      TestChatAccount(serverUrl = serverUrl, userId = userId)
    } else null
  }

  private fun loadPassword(): String? {
    val password = prefs.getString(passwordKey, null)?.trim().orEmpty()
    return password.ifBlank { null }
  }

  private fun loadHosts(): List<TestChatHost> {
    val raw = prefs.getString(hostsKey, null)?.trim().orEmpty()
    if (raw.isNotEmpty()) {
      val parsed =
        runCatching { json.decodeFromString(ListSerializer(TestChatHost.serializer()), raw) }
          .getOrNull()
      if (parsed != null) return parsed
    }
    val legacyToken = prefs.getString(legacyTokenKey, null)?.trim().orEmpty()
    return if (legacyToken.isNotBlank()) {
      listOf(TestChatHost(label = "default", token = legacyToken))
    } else {
      emptyList()
    }
  }

  private fun loadLastEventIds(): Map<String, Long> {
    val raw = prefs.getString(lastEventIdsKey, null)?.trim().orEmpty()
    if (raw.isNotEmpty()) {
      val parsed =
        runCatching {
          json.decodeFromString(
            MapSerializer(String.serializer(), Long.serializer()),
            raw,
          )
        }.getOrNull()
      if (parsed != null) return parsed
    }
    return emptyMap()
  }

  private fun loadLanguageTag(): String {
    return prefs.getString(languageKey, "system")?.trim().orEmpty().ifBlank { "system" }
  }

  private fun loadDisclaimerAccepted(): Boolean {
    return prefs.getBoolean(disclaimerKey, false)
  }
}
