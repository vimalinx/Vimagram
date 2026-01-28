package com.clawdbot.android.testchat

data class ChatIdentity(
  val machine: String,
  val session: String,
)

fun parseChatIdentity(chatId: String): ChatIdentity {
  val raw = chatId.trim()
  if (raw.isBlank()) return ChatIdentity("default", "session")
  val cleaned = raw.removePrefix("machine:").removePrefix("device:")
  if (cleaned.startsWith("user:")) {
    val session = cleaned.removePrefix("user:").ifBlank { "main" }
    return ChatIdentity("default", session)
  }
  val slash = cleaned.indexOf('/')
  if (slash > 0) {
    val machine = cleaned.substring(0, slash).ifBlank { "default" }
    val session = cleaned.substring(slash + 1).ifBlank { "main" }
    return ChatIdentity(machine, session)
  }
  val pipe = cleaned.indexOf('|')
  if (pipe > 0) {
    val machine = cleaned.substring(0, pipe).ifBlank { "default" }
    val session = cleaned.substring(pipe + 1).ifBlank { "main" }
    return ChatIdentity(machine, session)
  }
  val colon = cleaned.indexOf(':')
  if (colon > 0) {
    val machine = cleaned.substring(0, colon).ifBlank { "default" }
    val session = cleaned.substring(colon + 1).ifBlank { "main" }
    if (machine != "user" && machine != "test") {
      return ChatIdentity(machine, session)
    }
  }
  return ChatIdentity("default", cleaned)
}

fun resolveSessionLabel(thread: TestChatThread): String {
  if (thread.title.isNotBlank() && thread.title != thread.chatId) {
    return thread.title
  }
  return parseChatIdentity(thread.chatId).session
}
