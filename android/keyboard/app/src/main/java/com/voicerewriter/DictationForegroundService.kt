package com.voicerewriter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import helium314.keyboard.latin.R

/**
 * Keeps the microphone open while the keyboard is hidden mid-dictation (switching apps,
 * collapsing the keyboard, rotating). Android silences background capture unless a
 * microphone foreground service is running, so this starts with every dictation, while the
 * keyboard is still visible, and stops as soon as the recording ends.
 */
class DictationForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FINISH -> ImeDictationPipeline.finishDetached()
            ACTION_CANCEL -> ImeDictationPipeline.cancel(true)
            else -> runCatching {
                if (Build.VERSION.SDK_INT >= 29)
                    startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else startForeground(ID, notification())
            }.onFailure { stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Dictation", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.sym_keyboard_voice_rounded)
            .setContentTitle("OpenWhispr is listening")
            .setContentText("Reopen the keyboard to finish, or finish here to save it to history.")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Finish", action(ACTION_FINISH)).build())
            .addAction(Notification.Action.Builder(null, "Cancel", action(ACTION_CANCEL)).build())
            .build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(this, name.hashCode(),
        Intent(this, DictationForegroundService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    companion object {
        private const val CHANNEL = "dictation"
        private const val ID = 7301
        private const val ACTION_FINISH = "com.voicerewriter.dictation.FINISH"
        private const val ACTION_CANCEL = "com.voicerewriter.dictation.CANCEL"

        /** Call while the keyboard is visible; Android refuses microphone services started from the background. */
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, DictationForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { android.util.Log.w("DictationService", "foreground start refused", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DictationForegroundService::class.java)) }
        }
    }
}
