package com.autoorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val NOTI_PERM_REQ = 1001
    }

    private var currentMode: String = AppPrefs.MODE_WEB

    private lateinit var webView: WebView
    private lateinit var counter: TextView
    private lateinit var db: MessagesDb
    private lateinit var shopDb: ShopDb
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val orderDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_web)

        webView = findViewById(R.id.webView)
        counter = findViewById(R.id.counter)
        db = MessagesDb(this)
        shopDb = ShopDb(this)

        NewMsgNotifier.ensureChannels(this)
        requestNotificationPermissionIfNeeded()
        startKeepAliveService()

        findViewById<View>(R.id.btnHome).setOnClickListener { /* đang ở WebView */ }
        findViewById<View>(R.id.btnReload).setOnClickListener { webView.reload() }
        findViewById<View>(R.id.btnDump).setOnClickListener {
            triggerExtractSelected()
        }
        findViewById<View>(R.id.btnInbox).setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        currentMode = AppPrefs.getViewMode(this)
        val isMobile = currentMode == AppPrefs.MODE_MOBILE

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = if (isMobile) UA_MOBILE else UA_DESKTOP
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        if (!isMobile) webView.setInitialScale(25)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(JsBridge(), "AutoOrderBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                Log.i(TAG, "===== onPageFinished: $url =====")
                val mobileFlag = if (isMobile) "true" else "false"
                view.evaluateJavascript(
                    "window.__autoOrderMobileView = $mobileFlag;",
                    null
                )
                view.evaluateJavascript(ZALO_OBSERVER_JS, null)
                refreshCounter()
                mainHandler.postDelayed({ triggerDump() }, 4000L)
            }
        }

        webView.loadUrl(URL)
        refreshCounter()
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

    private fun startKeepAliveService() {
        val svc = Intent(this, WebMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
        else startService(svc)
    }

    private fun triggerDump() {
        webView.evaluateJavascript("window.__autoOrderDump && window.__autoOrderDump();", null)
    }

    private fun triggerExtractSelected() {
        Toast.makeText(this, "Đang trích xuất hội thoại đang chọn...", Toast.LENGTH_SHORT).show()
        webView.evaluateJavascript(
            "window.__autoOrderExtractSelected && window.__autoOrderExtractSelected();",
            null
        )
    }

    private fun refreshCounter() {
        ioExecutor.execute {
            val today = orderDateFormat.format(java.util.Date())
            val n = runCatching { shopDb.ordersCountToday(today) }.getOrDefault(0)
            mainHandler.post { counter.text = "$n đơn" }
        }
    }

    override fun onPause() {
        super.onPause()
        // KHÔNG gọi webView.onPause() — để WebView tiếp tục chạy JS observer khi
        // activity ở background. Chỉ resume timers cho chắc.
        webView.resumeTimers()
    }

    override fun onResume() {
        super.onResume()
        webView.resumeTimers()
        if (AppPrefs.getViewMode(this) != currentMode) {
            recreate()
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
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

            Log.d(TAG, "NEW from='$senderName' (anim=$animId) time='$timeText' :: $content")
            mainHandler.post { NewMsgNotifier.playPing(applicationContext) }
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
        fun onConversation(peerName: String, messagesJson: String) {
            Log.i(TAG, "EXTRACT peer='$peerName' msgs=${messagesJson.take(200)}")
            mainHandler.post {
                OrderExtractor.extractAndShow(this@ChatWebActivity, peerName, messagesJson)
            }
        }

        @JavascriptInterface
        fun onDump(tag: String, cssClass: String, text: String, dataAttrs: String) {
            val cls = if (cssClass.isBlank()) "" else " cls='${cssClass.take(120)}'"
            val data = if (dataAttrs.isBlank()) "" else " data='${dataAttrs.take(120)}'"
            Log.d(TAG, "DUMP <$tag>$cls$data :: $text")
        }
    }
}
