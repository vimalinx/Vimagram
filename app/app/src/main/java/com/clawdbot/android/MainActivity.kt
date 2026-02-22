package com.clawdbot.android

import android.Manifest
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.clawdbot.android.testchat.TestChatApp
import com.clawdbot.android.testchat.TestChatNotifier
import com.clawdbot.android.testchat.TestChatViewModel

class MainActivity : ComponentActivity() {
  private val viewModel: TestChatViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppLocale.apply(this, viewModel.languageTag.value)
    setContent {
      TestChatApp(viewModel = viewModel)
    }
    handleNotificationIntent(intent)
  }

  override fun onStart() {
    super.onStart()
    requestNotificationPermission()
    viewModel.setAppInForeground(true)
    handleNotificationIntent(intent)
  }

  override fun onStop() {
    viewModel.setAppInForeground(false)
    super.onStop()
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    handleNotificationIntent(intent)
  }

  private fun handleNotificationIntent(intent: android.content.Intent?) {
    val chatId = intent?.getStringExtra(TestChatNotifier.EXTRA_CHAT_ID)?.trim().orEmpty()
    if (chatId.isBlank()) return
    viewModel.openChatFromNotification(chatId)
    intent?.removeExtra(TestChatNotifier.EXTRA_CHAT_ID)
  }

  private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    if (prefs.getBoolean(KEY_NOTIFICATIONS_REQUESTED, false)) {
      return
    }
    prefs.edit().putBoolean(KEY_NOTIFICATIONS_REQUESTED, true).apply()
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
  }

  private companion object {
    const val REQUEST_NOTIFICATIONS = 2001
    const val PREFS_NAME = "clawdbot.testchat.prefs"
    const val KEY_NOTIFICATIONS_REQUESTED = "notifications.requested"
  }
}
