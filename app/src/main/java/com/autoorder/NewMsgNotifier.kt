package com.autoorder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object NewMsgNotifier {
    const val CHANNEL_NEW = "autoorder_messages"
    const val CHANNEL_SERVICE = "autoorder_service"
    private const val DEDUP_WINDOW_MS = 5_000L

    private val notiIdSeq = AtomicInteger(1000)
    private val recentHashes = ConcurrentHashMap<String, Long>()

    fun isDuplicate(animId: String, content: String): Boolean {
        val key = "$animId|$content"
        val now = System.currentTimeMillis()
        val last = recentHashes[key]
        if (last != null && now - last < DEDUP_WINDOW_MS) return true
        recentHashes[key] = now
        if (recentHashes.size > 500) {
            val cutoff = now - DEDUP_WINDOW_MS
            recentHashes.entries.removeAll { it.value < cutoff }
        }
        return false
    }

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEW, "Tin nhắn Zalo mới", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo khi có tin nhắn 1-1 mới đến trên Zalo Web"
                enableLights(true)
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "AutoOrder nền", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service giữ WebView Zalo chạy nền"
            }
        )
    }

    fun notifyNew(ctx: Context, senderName: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val intent = Intent(ctx, ChatWebActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (senderName.isBlank()) "Tin nhắn mới" else senderName
        val noti = NotificationCompat.Builder(ctx, CHANNEL_NEW)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching {
            NotificationManagerCompat.from(ctx).notify(notiIdSeq.getAndIncrement(), noti)
        }
    }
}
