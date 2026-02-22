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
