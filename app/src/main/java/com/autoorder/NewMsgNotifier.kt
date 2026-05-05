package com.autoorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object NewMsgNotifier {
    private const val TAG = "AutoOrder"
    const val CHANNEL_SERVICE = "autoorder_service"
    private const val DEDUP_WINDOW_MS = 5_000L

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

    /** Channel cho foreground service — Android 8+ bắt buộc. */
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, "AutoOrder nền", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service giữ app chạy nền"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    fun playPing(ctx: Context) {
        runCatching {
            val mp = MediaPlayer.create(ctx.applicationContext, R.raw.ping) ?: run {
                Log.w(TAG, "MediaPlayer.create returned null for R.raw.ping")
                return
            }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { player, _, _ -> player.release(); true }
            mp.start()
        }.onFailure { Log.e(TAG, "playPing failed", it) }
    }
}
