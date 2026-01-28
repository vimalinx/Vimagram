package com.clawdbot.android.testchat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.clawdbot.android.MainActivity
import com.clawdbot.android.R

class TestChatForegroundService : Service() {
  private var currentUserId: String? = null
  private var currentHostCount: Int = 0

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
    startForeground(NOTIFICATION_ID, buildNotification())
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopSelf()
        return START_NOT_STICKY
      }
    }
    intent?.getStringExtra(EXTRA_USER_ID)?.let { currentUserId = it }
    if (intent?.hasExtra(EXTRA_HOST_COUNT) == true) {
      currentHostCount = intent.getIntExtra(EXTRA_HOST_COUNT, currentHostCount)
    }
    updateNotification()
    return START_STICKY
  }

  override fun onBind(intent: Intent?) = null

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        "Vimagram",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "Keeps Vimagram active in the background"
        setShowBadge(false)
      }
    val mgr = getSystemService(NotificationManager::class.java)
    mgr.createNotificationChannel(channel)
  }

  private fun buildNotification(): Notification {
    val launchIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val launchPending =
      PendingIntent.getActivity(
        this,
        5,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent = Intent(this, TestChatForegroundService::class.java).setAction(ACTION_STOP)
    val stopPending =
      PendingIntent.getService(
        this,
        6,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val hostLabel =
      if (currentHostCount > 0) "$currentHostCount hosts" else "no hosts"
    val userLabel = currentUserId?.let { "user $it" } ?: "no user"
    val text = "$userLabel · $hostLabel"

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Vimagram running")
      .setContentText(text)
      .setContentIntent(launchPending)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .addAction(0, "Stop", stopPending)
      .build()
  }

  private fun updateNotification() {
    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.notify(NOTIFICATION_ID, buildNotification())
  }

  companion object {
    private const val CHANNEL_ID = "testchat_connection"
    private const val NOTIFICATION_ID = 101
    private const val ACTION_STOP = "com.clawdbot.android.testchat.STOP"
    private const val EXTRA_USER_ID = "extra_user_id"
    private const val EXTRA_HOST_COUNT = "extra_host_count"

    fun start(context: Context, userId: String?, hostCount: Int) {
      val intent =
        Intent(context, TestChatForegroundService::class.java).apply {
          putExtra(EXTRA_USER_ID, userId)
          putExtra(EXTRA_HOST_COUNT, hostCount)
        }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, TestChatForegroundService::class.java).setAction(ACTION_STOP)
      context.startService(intent)
    }
  }
}
