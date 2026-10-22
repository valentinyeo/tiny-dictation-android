package com.tinydictation.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.tinydictation.android.update.UpdateManager
import com.tinydictation.android.update.UpdateNotifier

class TinyDictationApp : Application() {

    val updateManager by lazy { UpdateManager(this) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        UpdateNotifier.initialize(this)
        updateManager.check()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "dictation_service_channel"
    }
}
