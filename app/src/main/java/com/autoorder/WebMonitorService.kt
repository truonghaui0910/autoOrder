package com.autoorder

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service giữ process AutoOrder ở priority cao để OS không kill khi activity
 * ở background. KHÔNG host WebView — WebView nằm trong [ChatWebActivity].
 *
 * Khi user swipe app khỏi recents → activity destroy → WebView destroy. Đó là giới hạn
 * khi không dùng overlay.
 */
class WebMonitorService : Service() {

    companion object {
        private const val TAG = "AutoOrder"
        private const val FG_NOTI_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[svc] onCreate (keep-alive)")
        NewMsgNotifier.ensureChannels(this)
        startForeground(FG_NOTI_ID, buildForegroundNoti())
        isRunning = true
    }

    private fun buildForegroundNoti(): android.app.Notification {
        val openIntent = Intent(this, ChatWebActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NewMsgNotifier.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("AutoOrder đang theo dõi Zalo")
            .setContentText("Chạm để mở app")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPi)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.i(TAG, "[svc] onDestroy")
    }
}
