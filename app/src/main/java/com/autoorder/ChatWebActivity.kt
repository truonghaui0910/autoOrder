package com.autoorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class ChatWebActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AutoOrder"
        private const val URL = "https://chat.zalo.me/"
        private const val UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val NOTI_PERM_REQ = 1001
    }

    private lateinit var webView: WebView
    private lateinit var counter: TextView
    private lateinit var db: MessagesDb
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_web)

        webView = findViewById(R.id.webView)
        counter = findViewById(R.id.counter)
        db = MessagesDb(this)

        NewMsgNotifier.ensureChannels(this)
        requestNotificationPermissionIfNeeded()

        findViewById<View>(R.id.btnHome).setOnClickListener { /* đang ở WebView */ }
        findViewById<View>(R.id.btnReload).setOnClickListener { webView.reload() }
        findViewById<View>(R.id.btnDump).setOnClickListener {
            triggerDump()
            Toast.makeText(this, "Đang quét DOM, xem Logcat", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnInbox).setOnClickListener {
            startActivity(Intent(this, MessagesActivity::class.java))
        }
        findViewById<View>(R.id.btnDb).setOnClickListener {
            startActivity(Intent(this, MessagesActivity::class.java))
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = UA_DESKTOP
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        webView.setInitialScale(25)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(JsBridge(), "AutoOrderBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                Log.i(TAG, "===== onPageFinished: $url =====")
                view.evaluateJavascript(ZALO_OBSERVER_JS, null)
                refreshCounter()
                mainHandler.postDelayed({ triggerDump() }, 4000L)
            }
        }

        webView.loadUrl(URL)
        refreshCounter()

        startBackgroundServiceOrPromptOverlay()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTI_PERM_REQ)
            }
        }
    }

    private fun startBackgroundServiceOrPromptOverlay() {
        if (Settings.canDrawOverlays(this)) {
            val svc = Intent(this, WebMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
        } else {
            Toast.makeText(
                this,
                "Cần cấp quyền 'Hiển thị trên ứng dụng khác' để chạy nền",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Sau khi user trở về từ màn hình cấp quyền overlay, thử start lại service
        if (Settings.canDrawOverlays(this) && !WebMonitorService.isRunning) {
            val svc = Intent(this, WebMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
        }
    }

    private fun triggerDump() {
        webView.evaluateJavascript("window.__autoOrderDump && window.__autoOrderDump();", null)
    }

    private fun refreshCounter() {
        ioExecutor.execute {
            val n = runCatching { db.count() }.getOrDefault(0L)
            mainHandler.post { counter.text = "$n tin" }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
        runCatching { db.close() }
    }

    inner class JsBridge {
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

            val effectiveSender =
                if (parsedSender.isNotBlank() && parsedSender != "Bạn") parsedSender
                else senderName

            Log.d(TAG, "NEW from='$senderName' (anim=$animId) time='$timeText' :: $content")
            mainHandler.post {
                NewMsgNotifier.notifyNew(
                    this@ChatWebActivity,
                    effectiveSender.ifBlank { senderName },
                    content
                )
            }
        }

        @JavascriptInterface
        fun onMessage(
            kind: String, convName: String, senderName: String,
            content: String, timeText: String, isSelf: Boolean, cssClass: String
        ) {
            if (content.isBlank()) return
            Log.d(
                TAG,
                "$kind self=$isSelf conv='$convName' from='$senderName' time='$timeText' :: $content"
            )
        }

        @JavascriptInterface
        fun onDump(tag: String, cssClass: String, text: String, dataAttrs: String) {
            val cls = if (cssClass.isBlank()) "" else " cls='${cssClass.take(120)}'"
            val data = if (dataAttrs.isBlank()) "" else " data='${dataAttrs.take(120)}'"
            Log.d(TAG, "DUMP <$tag>$cls$data :: $text")
        }
    }
}
