package com.clawdbot.android.testchat

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class TestChatStore(private val json: Json) {
  suspend fun load(context: Context, account: TestChatAccount): TestChatSnapshot {
    val file = historyFile(context, account)
    return withContext(Dispatchers.IO) {
      if (!file.exists()) {
        return@withContext TestChatSnapshot()
      }
      val raw = file.readText()
      runCatching { json.decodeFromString(TestChatSnapshot.serializer(), raw) }
        .getOrElse { TestChatSnapshot() }
    }
  }

  suspend fun save(context: Context, account: TestChatAccount, snapshot: TestChatSnapshot) {
    val file = historyFile(context, account)
    withContext(Dispatchers.IO) {
      if (!file.parentFile.exists()) {
        file.parentFile.mkdirs()
      }
      file.writeText(json.encodeToString(TestChatSnapshot.serializer(), snapshot))
    }
  }

  private fun historyFile(context: Context, account: TestChatAccount): File {
    val hash = sha256("${account.serverUrl}|${account.userId}")
    return File(context.filesDir, "testchat-history-$hash.json")
  }

  private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    val sb = StringBuilder(digest.size * 2)
    for (byte in digest) {
      sb.append(String.format("%02x", byte))
    }
    return sb.toString()
  }
}
