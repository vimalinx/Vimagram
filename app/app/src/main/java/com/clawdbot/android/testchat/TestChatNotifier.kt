package com.clawdbot.android.testchat

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.clawdbot.android.MainActivity
import com.clawdbot.android.R

class TestChatNotifier(private val app: Application) {
  private val manager = NotificationManagerCompat.from(app)

  init {
    createChannel()
  }

  fun notifyIncoming(
    chatId: String,
    message: TestChatMessage,
    isActive: Boolean,
    totalUnread: Int,
  ) {
    if (isActive) return
    val identity = parseChatIdentity(chatId)
    val title = "${identity.machine} · ${identity.session}"
    val snippet = message.text.replace(Regex("\\s+"), " ").trim()
    val text =
      if (snippet.length > 140) "${snippet.take(137)}..." else snippet.ifBlank { "(empty)" }
    val intent =
      Intent(app, MainActivity::class.java).apply {
        putExtra(EXTRA_CHAT_ID, chatId)
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val pendingIntent =
      PendingIntent.getActivity(
        app,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    val notification =
      NotificationCompat.Builder(app, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setSound(resolveSoundUri())
        .setNumber(totalUnread)
        .build()
    manager.notify(chatId, 0, notification)
  }

  private fun createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val soundUri = resolveSoundUri()
    val audioAttrs =
      AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        "Vimagram messages",
        NotificationManager.IMPORTANCE_HIGH,
      ).apply {
        description = "Messages from Vimagram"
        setSound(soundUri, audioAttrs)
      }
    val systemManager = app.getSystemService(NotificationManager::class.java)
    systemManager.createNotificationChannel(channel)
  }

  private fun resolveSoundUri(): Uri {
    return Uri.parse("android.resource://${app.packageName}/${R.raw.testchat_notify}")
  }

  companion object {
    const val CHANNEL_ID = "testchat_messages_v2"
    const val EXTRA_CHAT_ID = "extra_chat_id"
  }
}
