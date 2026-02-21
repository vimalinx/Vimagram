package com.clawdbot.android.testchat

import kotlinx.serialization.Serializable

@Serializable
data class TestChatAccount(
  val serverUrl: String,
  val userId: String,
)

@Serializable
data class TestChatHost(
  val label: String,
  val token: String,
)

@Serializable
data class TestChatTokenUsage(
  val token: String,
  val createdAt: Long? = null,
  val lastSeenAt: Long? = null,
  val streamConnects: Int? = null,
  val inboundCount: Int? = null,
  val outboundCount: Int? = null,
  val lastInboundAt: Long? = null,
  val lastOutboundAt: Long? = null,
)

@Serializable
data class TestChatCredentials(
  val serverUrl: String,
  val userId: String,
  val token: String,
)

@Serializable
data class TestChatMessage(
  val id: String,
  val chatId: String,
  val direction: String,
  val text: String,
  val timestampMs: Long,
  val senderName: String? = null,
  val sourceId: String? = null,
  val replyToId: String? = null,
  val deliveryStatus: String? = null,
)

@Serializable
data class TestChatThread(
  val chatId: String,
  val title: String,
  val lastMessage: String,
  val lastTimestampMs: Long,
  val instanceModelTierId: String? = null,
  val instanceIdentityId: String? = null,
  val unreadCount: Int = 0,
  val isPinned: Boolean = false,
  val isArchived: Boolean = false,
  val isDeleted: Boolean = false,
  val deletedAt: Long? = null,
)

@Serializable
data class TestChatSnapshot(
  val threads: List<TestChatThread> = emptyList(),
  val messages: List<TestChatMessage> = emptyList(),
)

enum class TestChatConnectionState {
  Disconnected,
  Connecting,
  Connected,
  Error,
}

data class TestChatSessionUsage(
  val chatId: String,
  val sessionLabel: String,
  val hostLabel: String,
  val tokenCount: Int,
  val lastTimestampMs: Long,
)

data class TestChatModeOption(
  val id: String,
  val title: String,
  val modelHint: String,
  val agentHint: String,
  val skillsHint: String,
  val demoOnly: Boolean = true,
)

object TestChatModeCatalog {
  const val QUICK = "quick"
  const val CODE = "code"
  const val DEEP = "deep"
  const val DEFAULT = QUICK

  val options: List<TestChatModeOption> =
    listOf(
      TestChatModeOption(
        id = QUICK,
        title = "Quick",
        modelHint = "gpt-4.1-mini",
        agentHint = "assistant-fast",
        skillsHint = "short-answer, recall",
      ),
      TestChatModeOption(
        id = CODE,
        title = "Code",
        modelHint = "gpt-5-coder",
        agentHint = "engineering",
        skillsHint = "coding-standards, backend-patterns",
      ),
      TestChatModeOption(
        id = DEEP,
        title = "Deep",
        modelHint = "claude-opus-4.1",
        agentHint = "deep-research",
        skillsHint = "iterative-retrieval, verification-loop",
      ),
    )

  fun normalizeModeId(raw: String?): String {
    val normalized = raw?.trim()?.lowercase().orEmpty()
    return if (options.any { it.id == normalized }) normalized else DEFAULT
  }

  fun resolveMode(raw: String?): TestChatModeOption {
    val normalized = normalizeModeId(raw)
    return options.firstOrNull { it.id == normalized } ?: options.first()
  }
}
