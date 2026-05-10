package com.autoorder

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service giữ process AutoOrder ở priority cao để OS không kill khi activity
 * ở background. KHÔNG host WebView — WebView nằm trong [ChatWebActivity].
 *
 * Ngoài keep-alive, service còn chạy 1 tick định kỳ tự động gọi [ChatWebActivity.requestScanConvs]
 * (chỉ quét conv đang hiển thị). Tick này độc lập với lifecycle activity: nếu tới giờ chạy
 * mà ChatWebActivity không sống → skip, tick kế tiếp thử lại. Cấu hình bật/tắt + interval
 * trong [AppPrefs].
 */
class WebMonitorService : Service() {

    companion object {
        private const val TAG = "AutoOrder"
        private const val FG_NOTI_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: WebMonitorService? = null

        /** Gọi sau khi user đổi cấu hình auto-sync trong Settings. */
        fun reschedule() { instance?.scheduleNextTick() }
    }

    private val tickHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = Runnable { runTickAndReschedule() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "[svc] onCreate (keep-alive)")
        NewMsgNotifier.ensureChannels(this)
        startForeground(FG_NOTI_ID, buildForegroundNoti())
        isRunning = true
        instance = this
        scheduleNextTick()
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

    private fun scheduleNextTick() {
        tickHandler.removeCallbacks(tickRunnable)
        if (!AppPrefs.isAutoSyncEnabled(this)) {
            Log.i(TAG, "[svc] auto-sync disabled, no tick scheduled")
            return
        }
        val intervalMs = AppPrefs.getAutoSyncIntervalMin(this) * 60_000L
        tickHandler.postDelayed(tickRunnable, intervalMs)
        Log.i(TAG, "[svc] next auto-sync tick in ${intervalMs / 1000}s")
    }

    private fun runTickAndReschedule() {
        try {
            if (!AppPrefs.isAutoSyncEnabled(this)) {
                Log.i(TAG, "[svc] tick skipped: disabled")
            } else {
                val ok = ChatWebActivity.requestScanConvs()
                Log.i(TAG, if (ok) "[svc] tick → scanConvs dispatched"
                else "[svc] tick skipped: ChatWebActivity not alive")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "[svc] tick failed: ${t.message}")
        } finally {
            scheduleNextTick()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tickHandler.removeCallbacks(tickRunnable)
        if (instance === this) instance = null
        isRunning = false
        Log.i(TAG, "[svc] onDestroy")
    }
}
