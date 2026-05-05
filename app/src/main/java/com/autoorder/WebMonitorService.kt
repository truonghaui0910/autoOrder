package com.autoorder

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat

class WebMonitorService : Service() {

    companion object {
        private const val TAG = "AutoOrder"
        private const val URL = "https://chat.zalo.me/"
        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val FG_NOTI_ID = 1

        @Volatile
        var isRunning: Boolean = false
            private set

        fun canRun(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
    }

    private var webView: WebView? = null
    private var wm: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NewMsgNotifier.ensureChannels(this)
        startForeground(FG_NOTI_ID, buildForegroundNoti())
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "WebMonitorService: thiếu SYSTEM_ALERT_WINDOW, dừng")
            stopSelf()
            return
        }
        attachHeadlessWebView()
        isRunning = true
    }

    private fun buildForegroundNoti(): android.app.Notification {
        val intent = Intent(this, ChatWebActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NewMsgNotifier.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("AutoOrder đang theo dõi Zalo")
            .setContentText("Chạy nền — chạm để mở")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun attachHeadlessWebView() {
        val wv = WebView(this)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = UA_DESKTOP
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        wv.setInitialScale(25)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.addJavascriptInterface(JsBridge(applicationContext), "AutoOrderBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                Log.i(TAG, "[svc] onPageFinished: $url")
                view.evaluateJavascript(ZALO_OBSERVER_JS, null)
            }
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            1, 1,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { wm?.addView(wv, params) }
            .onFailure { Log.e(TAG, "addView failed", it); stopSelf() }
        wv.loadUrl(URL)
        webView = wv
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        webView?.let { wv ->
            runCatching { wm?.removeView(wv) }
            runCatching { wv.stopLoading() }
            runCatching { wv.destroy() }
        }
        webView = null
        wm = null
    }

    private class JsBridge(private val appCtx: Context) {
        @JavascriptInterface
        fun onNewMessage(animId: String, senderName: String, preview: String, timeText: String) {
            if (preview.isBlank()) return
            var content = preview
            val m = Regex("^([^:]{1,40}):\\s+(.*)$").find(preview)
            val parsedSender = if (m != null) {
                content = m.groupValues[2].trim()
                m.groupValues[1].trim()
            } else senderName
            if (parsedSender.equals("Bạn", ignoreCase = true)) return

            if (NewMsgNotifier.isDuplicate(animId, content)) return

            val effective = if (parsedSender.isNotBlank() && parsedSender != "Bạn") parsedSender
                else senderName
            Log.d(TAG, "[svc] NEW from='$senderName' (anim=$animId) :: $content")
            NewMsgNotifier.notifyNew(appCtx, effective.ifBlank { senderName }, content)
        }

        @JavascriptInterface
        fun onMessage(
            kind: String, convName: String, senderName: String,
            content: String, timeText: String, isSelf: Boolean, cssClass: String
        ) {
            // không cần xử lý ở service, chỉ log
            if (content.isNotBlank()) {
                Log.v(TAG, "[svc] $kind self=$isSelf '$senderName' :: $content")
            }
        }

        @JavascriptInterface
        fun onDump(tag: String, cssClass: String, text: String, dataAttrs: String) {
            Log.d(TAG, "[svc] DUMP <$tag> :: $text")
        }
    }
}
