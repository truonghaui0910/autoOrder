package com.autoorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import coil.load
import java.util.concurrent.Executors

class ChatWebActivity : AppCompatActivity() {

    private data class ReplyItem(
        val sender: String,
        val text: String,
        val timeMs: Long,
        val reactionEmoji: String,
        val reactionCount: Int
    )

    companion object {
        private const val TAG = "AutoOrder"
        private const val URL = "https://chat.zalo.me/"

        private fun mapZaloEmoji(code: String): String = when (code.trim()) {
            "/-strong" -> "👍"
            "/-heart" -> "❤"
            "/-weak" -> "👎"
            ":>" -> "😄"
            ":o" -> "😮"
            ":-((" -> "😢"
            ":'(" -> "😢"
            ":-h" -> "🤝"
            ":-bd" -> "👏"
            else -> code
        }

        @Volatile
        private var liveInstance: java.lang.ref.WeakReference<ChatWebActivity>? = null

        fun requestScanConvs(): Boolean {
            val act = liveInstance?.get() ?: return false
            act.runOnUiThread { act.triggerScanConvs() }
            return true
        }

        fun requestScanConvsRecent(hours: Double, onDone: () -> Unit): Boolean {
            val act = liveInstance?.get() ?: return false
            act.runOnUiThread { act.triggerScanConvsRecent(hours, onDone) }
            return true
        }

        fun requestSendChat(text: String, onResult: (String) -> Unit): Boolean {
            val act = liveInstance?.get() ?: return false
            act.runOnUiThread { act.triggerSendChat(text, onResult) }
            return true
        }

        fun requestSearch(query: String, mode: String, onDone: (String, String) -> Unit): Boolean {
            val act = liveInstance?.get() ?: return false
            act.runOnUiThread { act.triggerSearch(query, mode, onDone) }
            return true
        }

        private const val SEND_CHAT_JS = """
(function(){
  try {
    var text = '__TEXT__';
    var rich = document.getElementById('richInput');
    if (!rich) {
      var nodes = document.querySelectorAll('div[contenteditable="true"], [contenteditable=""], .rich-input');
      for (var j=nodes.length-1; j>=0; j--) {
        var r = nodes[j].getBoundingClientRect();
        if (r.width > 50 && r.height > 10) { rich = nodes[j]; break; }
      }
    }
    if (!rich) return 'NO_INPUT';
    try { rich.setAttribute('contenteditable', 'true'); } catch(e) {}
    rich.focus();

    try {
      var sel = window.getSelection();
      var range = document.createRange();
      range.selectNodeContents(rich);
      sel.removeAllRanges();
      sel.addRange(range);
      document.execCommand('delete', false, null);
    } catch(e) {}

    var dt = new DataTransfer();
    try { dt.setData('text/plain', text); } catch(e) {}
    var pasted = false;
    try {
      var ev = new ClipboardEvent('paste', { bubbles:true, cancelable:true, clipboardData: dt });
      try { Object.defineProperty(ev, 'clipboardData', { value: dt, configurable: true }); } catch(_){}
      rich.dispatchEvent(ev);
      pasted = true;
    } catch(e) {}

    if (!pasted || (rich.innerText || '').trim().length === 0) {
      try {
        var lines = text.split('\n');
        rich.innerHTML = '';
        for (var i=0; i<lines.length; i++) {
          var div = document.createElement('div');
          if (lines[i].length === 0) div.appendChild(document.createElement('br'));
          else div.appendChild(document.createTextNode(lines[i]));
          rich.appendChild(div);
        }
      } catch(e) {}
    }
    try { rich.classList.remove('empty'); } catch(e) {}

    try {
      var sel2 = window.getSelection();
      var r2 = document.createRange();
      r2.selectNodeContents(rich);
      r2.collapse(false);
      sel2.removeAllRanges();
      sel2.addRange(r2);
    } catch(e) {}

    try { rich.dispatchEvent(new Event('input', { bubbles:true })); } catch(e) {}
    try { rich.dispatchEvent(new Event('change', { bubbles:true })); } catch(e) {}
    try { rich.dispatchEvent(new KeyboardEvent('keyup', { bubbles:true, key:'a' })); } catch(e) {}

    function dismissContactPreview() {
      try {
        var closers = document.querySelectorAll('.preview-contact-wrapper .close__preview, .preview-contact .close__preview');
        for (var i=0; i<closers.length; i++) {
          try { closers[i].click(); } catch(_) {}
        }
      } catch(_) {}
      try {
        var wraps = document.querySelectorAll('.preview-contact-wrapper');
        for (var k=0; k<wraps.length; k++) {
          try { wraps[k].parentNode && wraps[k].parentNode.removeChild(wraps[k]); } catch(_) {}
        }
      } catch(_) {}
    }

    var d1 = 900 + Math.floor(Math.random() * 400);
    var d2 = 700 + Math.floor(Math.random() * 400);
    setTimeout(function(){
      dismissContactPreview();
      setTimeout(function(){
        var btn = document.querySelector('.send-msg-btn');
        if (!btn) {
          try { AutoOrderBridge.onSendChatResult('NO_BTN'); } catch(e) {}
          return;
        }
        try {
          var r = btn.getBoundingClientRect();
          var cx = r.left + r.width / 2;
          var cy = r.top + r.height / 2;
          ['mouseover','mouseenter','mousemove','mousedown','mouseup','click'].forEach(function(t){
            try {
              btn.dispatchEvent(new MouseEvent(t, {
                bubbles: true, cancelable: true, view: window,
                clientX: cx, clientY: cy, button: 0
              }));
            } catch(_) {}
          });
          AutoOrderBridge.onSendChatResult('OK');
        } catch (e) {
          try { AutoOrderBridge.onSendChatResult('ERR_' + (e && e.message ? e.message : e)); } catch(_) {}
        }
      }, d2);
    }, d1);
    return 'PENDING';
  } catch (e) {
    return 'ERR_' + (e && e.message ? e.message : e);
  }
})();
"""

        private const val PASTE_QR_JS = """
(function(){
  try {
    var dataUrl = '__DATA_URL__';
    var parts = dataUrl.split(',');
    var bin = atob(parts[1]);
    var ab = new ArrayBuffer(bin.length);
    var ia = new Uint8Array(ab);
    for (var i=0; i<bin.length; i++) ia[i] = bin.charCodeAt(i);
    var blob = new Blob([ab], {type:'image/png'});
    var file = new File([blob], 'qr.png', {type:'image/png', lastModified: Date.now()});
    var dt = new DataTransfer();
    dt.items.add(file);

    var target = (document.activeElement && document.activeElement.isContentEditable)
      ? document.activeElement : null;
    if (!target) {
      var nodes = document.querySelectorAll('div[contenteditable="true"], [contenteditable=""]');
      for (var j=nodes.length-1; j>=0; j--) {
        var r = nodes[j].getBoundingClientRect();
        if (r.width > 50 && r.height > 10) { target = nodes[j]; break; }
      }
    }
    if (!target) return 'NO_INPUT';
    target.focus();

    var ev;
    try {
      ev = new ClipboardEvent('paste', { bubbles:true, cancelable:true, clipboardData: dt });
    } catch(e) {
      ev = document.createEvent('Event');
      ev.initEvent('paste', true, true);
    }
    try { Object.defineProperty(ev, 'clipboardData', { value: dt, configurable: true }); } catch(e){}
    target.dispatchEvent(ev);
    return 'OK';
  } catch (e) {
    return 'ERR_' + (e && e.message ? e.message : e);
  }
})();
"""
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
    private lateinit var qrPasteBar: View
    private lateinit var btnPasteQr: android.widget.Button
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

        runCatching { deleteDatabase("autoorder.db") }

        liveInstance = java.lang.ref.WeakReference(this)
        webView = findViewById(R.id.webView)
        counter = findViewById(R.id.counter)
        qrPasteBar = findViewById(R.id.qrPasteBar)
        btnPasteQr = findViewById(R.id.btnPasteQr)
        shopDb = ShopDb(this)

        btnPasteQr.setOnClickListener { pastePendingQr() }
        findViewById<View>(R.id.btnPasteQrClose).setOnClickListener {
            PendingQr.clear()
            updatePasteQrVisibility()
        }

        NewMsgNotifier.ensureChannels(this)
        requestNotificationPermissionIfNeeded()
        startKeepAliveService()

        findViewById<View>(R.id.btnHome).setOnClickListener { /* đang ở WebView */ }
        findViewById<View>(R.id.btnCheckout).setOnClickListener {
            val d = checkoutDialog
            if (d != null) d.show() else openCheckoutDialog()
        }
        findViewById<View>(R.id.btnDump).setOnClickListener {
            if (!OrderExtractor.restore()) triggerExtractSelected()
        }
        OrderExtractor.onActiveChanged = { active ->
            mainHandler.post { setBottomBarItemActive(R.id.btnDump, active) }
        }
        setBottomBarItemActive(R.id.btnDump, OrderExtractor.hasActive())
        setBottomBarItemActive(R.id.btnCheckout, false)
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
                mainHandler.postDelayed({
                    triggerDump()
                    triggerScanConvs()
                }, 4000L)
            }
        }

        webView.loadUrl(URL)
        refreshCounter()

        if (intent?.getBooleanExtra("open_checkout", false) == true) {
            mainHandler.post { openCheckoutDialog() }
        }
        handleCheckPaymentIntent(intent)

        OrderExtractor.onOrderSaved = { mainHandler.post { refreshCounter() } }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        handleCheckPaymentIntent(intent)
    }

    private var checkPaymentDialog: android.app.Dialog? = null

    private fun handleCheckPaymentIntent(intent: Intent?) {
        if (intent == null) return
        val orderId = intent.getLongExtra("check_payment_order_id", -1L)
        val phone = intent.getStringExtra("check_payment_phone").orEmpty()
        if (orderId <= 0 || phone.isBlank()) return
        // Consume extras để onResume sau không trigger lại.
        intent.removeExtra("check_payment_order_id")
        intent.removeExtra("check_payment_phone")
        val total = intent.getLongExtra("check_payment_total", 0L)
        val sender = intent.getStringExtra("check_payment_sender").orEmpty()
        val orderAvatar = intent.getStringExtra("check_payment_avatar").orEmpty()
        intent.removeExtra("check_payment_total")
        intent.removeExtra("check_payment_sender")
        intent.removeExtra("check_payment_avatar")

        val progress = android.app.Dialog(this, R.style.TransparentDialog)
        progress.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.bg_dialog_card)
        }
        box.addView(android.widget.ProgressBar(this).apply { isIndeterminate = true },
            android.widget.LinearLayout.LayoutParams(
                (24 * density).toInt(), (24 * density).toInt()
            ).apply { marginEnd = (12 * density).toInt() })
        box.addView(TextView(this).apply {
            text = "Đang tìm \"$phone\" trên Zalo..."
            textSize = 14f
            setTextColor(0xFF263238.toInt())
        })
        progress.setContentView(box)
        progress.setCancelable(true)
        progress.setCanceledOnTouchOutside(true)
        progress.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.85).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val cancelled = booleanArrayOf(false)
        progress.setOnCancelListener {
            cancelled[0] = true
            Toast.makeText(this, "Đã huỷ kiểm tra", Toast.LENGTH_SHORT).show()
        }
        progress.show()

        // Cho WebView 600ms để stable sau khi vừa resume từ background.
        mainHandler.postDelayed({
            if (cancelled[0]) return@postDelayed
            triggerCheckPaymentByPhone(phone) { status, _, peerName, avatarUrl, json ->
                if (cancelled[0]) return@triggerCheckPaymentByPhone
                if (progress.isShowing) progress.dismiss()
                when (status) {
                    "OK" -> showCheckPaymentDialog(
                        orderId, phone, total, sender, peerName,
                        // Ưu tiên avatar đã lưu của Order (lookup từ DB ở Orders);
                        // chỉ fallback sang avatar JS lấy từ sidebar khi DB trống.
                        if (orderAvatar.isNotBlank()) orderAvatar else avatarUrl,
                        json
                    )
                    "NO_RESULT" -> {
                        Toast.makeText(this, "Không tìm thấy SĐT \"$phone\" trên Zalo", Toast.LENGTH_LONG).show()
                        navigateBackToOrders()
                    }
                    "NO_INPUT" -> {
                        Toast.makeText(this, "Không tìm thấy ô tìm kiếm Zalo", Toast.LENGTH_LONG).show()
                        navigateBackToOrders()
                    }
                    "NO_CONV_OPENED" -> {
                        Toast.makeText(this, "Tìm thấy nhưng không mở được hội thoại", Toast.LENGTH_LONG).show()
                        navigateBackToOrders()
                    }
                    "EMPTY", "NO_CONTAINER" -> {
                        Toast.makeText(this, "Không đọc được tin nhắn ($status)", Toast.LENGTH_LONG).show()
                        navigateBackToOrders()
                    }
                    else -> {
                        Toast.makeText(this, "Lỗi: $status", Toast.LENGTH_LONG).show()
                        navigateBackToOrders()
                    }
                }
            }
        }, 600L)
    }

    private fun navigateBackToOrders(focusOrderId: Long = -1L) {
        // Với launchMode=singleTask của Chat, Orders đã bị clear khi Chat brought-up.
        // Phải startActivity tường minh để user quay lại danh sách đơn hàng.
        // focusOrderId > 0 → Orders sẽ scroll & highlight đúng row đó.
        val i = Intent(this, OrdersActivity::class.java)
        if (focusOrderId > 0) i.putExtra("focus_order_id", focusOrderId)
        startActivity(i)
    }

    private fun showCheckPaymentDialog(
        orderId: Long, phone: String, totalAmount: Long, senderName: String,
        peerName: String, avatarUrl: String, @Suppress("UNUSED_PARAMETER") messagesJson: String
    ) {
        val dialog = android.app.Dialog(this, R.style.TransparentDialog)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val density = resources.displayMetrics.density
        val isMobileLayout = resources.configuration.smallestScreenWidthDp < 600
        val padH = ((if (isMobileLayout) 4 else 12) * density).toInt()
        val padV = (10 * density).toInt()
        val priceFmt = java.text.NumberFormat.getInstance(java.util.Locale("vi", "VN"))

        // Modal nhỏ pin ở đáy màn hình, không dim, không chặn touch ngoài
        // → user vẫn cuộn/click được Zalo WebView phía sau để đọc tin nhắn.
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_dialog_card)
            setPadding(padH, padV, padH, padV)
            elevation = 8 * density
        }

        val avSize = (32 * density).toInt()
        val avatar = android.widget.ImageView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(avSize, avSize).apply {
                marginEnd = (8 * density).toInt()
            }
            setBackgroundResource(R.drawable.bg_avatar_placeholder)
        }
        if (avatarUrl.isNotBlank()) {
            avatar.load(avatarUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_avatar_placeholder)
                error(R.drawable.bg_avatar_placeholder)
                transformations(coil.transform.CircleCropTransformation())
            }
        }
        row.addView(avatar)

        val info = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        info.addView(TextView(this).apply {
            text = peerName.ifBlank { senderName.ifBlank { phone } }
            textSize = 13f
            setTextColor(0xFF1E88E5.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(TextView(this).apply {
            text = "#$orderId · ${priceFmt.format(totalAmount)}₫"
            textSize = 10f
            setTextColor(0xFF78909C.toInt())
            maxLines = 1
        })
        row.addView(info)

        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        val displayName = peerName.ifBlank { senderName.ifBlank { phone } }

        val iconSize = (28 * density).toInt()
        val btnCopyName = android.widget.ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_myplaces)
            setBackgroundResource(R.drawable.bg_btn_outline)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginStart = (6 * density).toInt()
            }
            setOnClickListener {
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("name", displayName)
                )
                Toast.makeText(this@ChatWebActivity, "Đã copy tên", Toast.LENGTH_SHORT).show()
            }
        }
        val btnCopyPhone = android.widget.ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_call)
            setBackgroundResource(R.drawable.bg_btn_outline)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = android.widget.LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginStart = (6 * density).toInt()
            }
            setOnClickListener {
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("phone", phone)
                )
                Toast.makeText(this@ChatWebActivity, "Đã copy SĐT", Toast.LENGTH_SHORT).show()
            }
        }

        val btnNo = android.widget.Button(this).apply {
            text = "Chưa"
            isAllCaps = false
            textSize = 12f
            setTextColor(0xFF546E7A.toInt())
            setBackgroundResource(R.drawable.bg_btn_outline)
            minWidth = (64 * density).toInt()
            minimumWidth = (64 * density).toInt()
            val pH = (10 * density).toInt()
            val pV = (4 * density).toInt()
            setPadding(pH, pV, pH, pV)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt()
            ).apply { marginStart = (6 * density).toInt() }
        }
        val btnYes = android.widget.Button(this).apply {
            text = "Đã CK"
            isAllCaps = false
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_btn_primary)
            minWidth = (72 * density).toInt()
            minimumWidth = (72 * density).toInt()
            val pH = (10 * density).toInt()
            val pV = (4 * density).toInt()
            setPadding(pH, pV, pH, pV)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt()
            ).apply { marginStart = (6 * density).toInt() }
        }
        btnNo.setOnClickListener {
            dialog.dismiss()
            navigateBackToOrders(orderId)
        }
        btnYes.setOnClickListener {
            if (!ioExecutor.isShutdown) {
                ioExecutor.execute {
                    runCatching { shopDb.setOrderPaid(orderId, true) }
                    mainHandler.post {
                        Toast.makeText(this, "Đã đánh dấu TT đơn #$orderId", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        navigateBackToOrders(orderId)
                    }
                }
            }
        }
        row.addView(btnCopyName)
        row.addView(btnCopyPhone)
        row.addView(btnNo)
        row.addView(btnYes)

        dialog.setContentView(row)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            // Không dim, không chặn touch ngoài modal.
            setDimAmount(0f)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            val attrs = attributes
            val isMobile = resources.configuration.smallestScreenWidthDp < 600
            attrs.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            attrs.x = 0
            attrs.y = ((if (isMobile) 125 else 100) * density).toInt()
            attributes = attrs
            val w = if (isMobile) ViewGroup.LayoutParams.MATCH_PARENT
                    else (resources.displayMetrics.widthPixels * 0.35).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener {
            if (checkPaymentDialog === dialog) checkPaymentDialog = null
        }
        checkPaymentDialog = dialog
        dialog.show()
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

    fun triggerScanConvs() {
        webView.evaluateJavascript(
            "window.__autoOrderScanConvs && window.__autoOrderScanConvs();", null
        )
    }

    private var scanRecentDone: (() -> Unit)? = null
    private var searchDone: ((String, String) -> Unit)? = null
    private var paymentCheckDone: ((String, String, String, String, String) -> Unit)? = null
    private var sendChatDone: ((String) -> Unit)? = null

    private fun triggerSendChat(text: String, onResult: (String) -> Unit) {
        sendChatDone = onResult
        val escaped = text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
        val js = SEND_CHAT_JS.replace("__TEXT__", escaped)
        webView.evaluateJavascript(js) { result ->
            val r = (result ?: "").trim('"')
            if (r.startsWith("ERR_") || r == "NO_INPUT") {
                val cb = sendChatDone
                sendChatDone = null
                cb?.invoke(r)
            }
        }
    }

    fun triggerFetchPaymentCheck(onDone: (String, String, String, String, String) -> Unit) {
        paymentCheckDone = onDone
        webView.evaluateJavascript(
            "window.__autoOrderFetchPaymentCheck && window.__autoOrderFetchPaymentCheck(10);", null
        )
    }

    fun triggerCheckPaymentByPhone(
        phone: String,
        onDone: (String, String, String, String, String) -> Unit
    ) {
        paymentCheckDone = onDone
        val safePhone = org.json.JSONObject.quote(phone)
        webView.evaluateJavascript(
            "window.__autoOrderCheckPaymentByPhone && window.__autoOrderCheckPaymentByPhone($safePhone, 10);",
            null
        )
    }

    /**
     * Type [query] into Zalo's contact-search and click matching result, then close
     * the search panel. [mode] = "phone" or "name". Does NOT call scanConvs — caller
     * chains that. Result reported via [onDone] (status: OK/NO_RESULT/NO_INPUT, msg).
     */
    fun triggerSearch(query: String, mode: String, onDone: (String, String) -> Unit) {
        searchDone = onDone
        val safeQ = org.json.JSONObject.quote(query)
        val safeMode = org.json.JSONObject.quote(mode)
        webView.evaluateJavascript(
            "window.__autoOrderSearch && window.__autoOrderSearch($safeQ, $safeMode);", null
        )
    }

    fun triggerScanConvsRecent(hours: Double, onDone: () -> Unit) {
        scanRecentDone = onDone
        webView.evaluateJavascript(
            "window.__autoOrderScanRecent && window.__autoOrderScanRecent($hours);", null
        )
    }

    private var checkoutCallback: ((String, String, String) -> Unit)? = null
    private var checkoutDialog: android.app.Dialog? = null

    /**
     * Nút icon: copy [value] vào clipboard, đồng thời search trên Zalo theo
     * [searchMode] ("name"/"phone") rồi ẩn modal chốt đơn.
     */
    private fun buildCopyIcon(
        iconRes: Int, tint: Int, density: Float, label: String, value: String,
        searchMode: String? = null
    ): View {
        val size = (24 * density).toInt()
        val pad = (4 * density).toInt()
        val iv = android.widget.ImageView(this)
        iv.setImageResource(iconRes)
        iv.setColorFilter(tint)
        iv.setPadding(pad, pad, pad, pad)
        iv.isClickable = true
        iv.isFocusable = true
        iv.background = androidx.core.content.ContextCompat.getDrawable(
            this, R.drawable.bg_input_field
        )
        iv.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
            Toast.makeText(this, "Đã copy: $value", Toast.LENGTH_SHORT).show()
            if (searchMode != null && value.isNotBlank()) {
                checkoutDialog?.hide()
                triggerSearch(value, searchMode) { status, _ ->
                    if (status != "OK") {
                        showErrorToast(when (status) {
                            "NO_RESULT" -> "Không tìm thấy \"$value\" trên Zalo"
                            "NO_INPUT" -> "Không tìm thấy ô tìm kiếm Zalo"
                            else -> "Search lỗi: $status"
                        })
                    }
                }
            }
        }
        val lp = android.widget.LinearLayout.LayoutParams(size, size)
        lp.marginStart = (4 * density).toInt()
        iv.layoutParams = lp
        return iv
    }

    private fun showStatusToast(message: String, success: Boolean) {
        val tv = TextView(this)
        tv.text = message
        tv.setTextColor(if (success) 0xFF1B5E20.toInt() else 0xFFB71C1C.toInt())
        tv.textSize = 13f
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.background = androidx.core.content.ContextCompat.getDrawable(
            this,
            if (success) R.drawable.bg_toast_success else R.drawable.bg_toast_error
        )
        val pad = (12 * resources.displayMetrics.density).toInt()
        tv.setPadding(pad, pad - 2, pad, pad - 2)
        val toast = Toast(this)
        toast.duration = if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        @Suppress("DEPRECATION")
        toast.view = tv
        toast.show()
    }

    private fun showErrorToast(message: String) = showStatusToast(message, success = false)
    private fun showSuccessToast(message: String) = showStatusToast(message, success = true)

    private fun setBottomBarItemActive(itemId: Int, active: Boolean) {
        val item = findViewById<android.widget.LinearLayout>(itemId) ?: return
        val activeColor = 0xFFFF9800.toInt()
        val inactiveIcon = 0xFF1976D2.toInt()
        val inactiveText = 0xFF37474F.toInt()
        for (i in 0 until item.childCount) {
            when (val child = item.getChildAt(i)) {
                is android.widget.ImageView -> child.setColorFilter(if (active) activeColor else inactiveIcon)
                is TextView -> {
                    child.setTextColor(if (active) activeColor else inactiveText)
                    child.setTypeface(
                        null,
                        if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                    )
                }
            }
        }
        item.isActivated = active
    }

    private fun openCheckoutDialog() {
        checkoutDialog?.let { existing ->
            if (!existing.isShowing) existing.show()
            return
        }
        val chats = shopDb.listZaloChats().filter {
            it.status == "active" && it.chatType == "order"
        }
        if (chats.isEmpty()) {
            Toast.makeText(
                this,
                "Chưa có hội thoại nào có chatType='order' và status='active'",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_checkout, null, false)
        val spChat = view.findViewById<android.widget.Spinner>(R.id.spChat)
        val tvDate = view.findViewById<TextView>(R.id.tvDate)
        val btnRead = view.findViewById<android.widget.Button>(R.id.btnReadMessages)
        val pbRead = view.findViewById<android.widget.ProgressBar>(R.id.pbReadMessages)
        val btnAnalyze = view.findViewById<android.widget.Button>(R.id.btnAnalyze)
        val btnSaveAll = view.findViewById<android.widget.Button>(R.id.btnSaveAll)
        val pbSaveAll = view.findViewById<android.widget.ProgressBar>(R.id.pbSaveAll)
        val btnDismiss = view.findViewById<View>(R.id.btnDismiss)
        val btnMinimize = view.findViewById<View>(R.id.btnMinimize)
        btnAnalyze.isEnabled = false
        btnSaveAll.isEnabled = false
        val analyzeBtnDefaultText = btnAnalyze.text?.toString() ?: "2. Phân tích"
        var analyzeCancel: java.util.concurrent.atomic.AtomicBoolean? = null
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
        val tvMessagesLabel = view.findViewById<TextView>(R.id.tvMessagesLabel)
        val messagesContainer = view.findViewById<android.widget.LinearLayout>(R.id.messagesContainer)
        val btnFilterUnmapped = view.findViewById<TextView>(R.id.btnFilterUnmapped)
        val btnFilterUnsaved = view.findViewById<TextView>(R.id.btnFilterUnsaved)
        val btnFilterUnpaid = view.findViewById<TextView>(R.id.btnFilterUnpaid)
        val btnFilterConfirmed = view.findViewById<TextView>(R.id.btnFilterConfirmed)
        val btnFilterUnconfirmed = view.findViewById<TextView>(R.id.btnFilterUnconfirmed)
        val btnFilterFreeship = view.findViewById<TextView>(R.id.btnFilterFreeship)
        val btnFilterDone = view.findViewById<TextView>(R.id.btnFilterDone)
        val btnFilterBooked = view.findViewById<TextView>(R.id.btnFilterBooked)
        val etSearch = view.findViewById<android.widget.EditText>(R.id.etSearch)
        val filterScroll = view.findViewById<View>(R.id.filterScroll)

        val labels = chats.map { it.name.ifBlank { it.zaloId } }
        spChat.adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )

        val displayFmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("vi", "VN")).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
        tvDate.text = displayFmt.format(cal.time)
        tvDate.setOnClickListener {
            android.app.DatePickerDialog(this, { _, y, m, d ->
                val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
                c.set(y, m, d, 0, 0, 0)
                cal.timeInMillis = c.timeInMillis
                tvDate.text = displayFmt.format(cal.time)
            }, cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }

        val dialog = android.app.Dialog(this, R.style.TransparentDialog)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.setCancelable(true)
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            val w = (resources.displayMetrics.widthPixels * 0.94).toInt()
            val h = (resources.displayMetrics.heightPixels * 0.88).toInt()
            setLayout(w, h)
        }

        btnDismiss.setOnClickListener { dialog.dismiss() }
        btnMinimize.setOnClickListener { dialog.hide() }
        checkoutDialog = dialog
        setBottomBarItemActive(R.id.btnCheckout, true)

        var texts: List<String> = emptyList()
        var timesMs: List<Long> = emptyList()
        var replies: List<List<ReplyItem>> = emptyList()
        var confirmed: List<Boolean> = emptyList()
        var filterUnmapped = false
        var filterUnsaved = false
        var filterUnpaid = false
        var filterConfirmed = false
        var filterUnconfirmed = false
        var filterFreeship = false
        var filterDone = false
        var filterBooked = false
        var searchQuery = ""

        fun normSender(s: String) = s.trim().lowercase(java.util.Locale.ROOT)
        val freeshipSenders = runCatching { shopDb.getNamesByChatType("shipper") }
            .getOrDefault(emptyList()).map { normSender(it) }.toSet()
        val doneSenders = runCatching { shopDb.getNamesByChatType("bartender") }
            .getOrDefault(emptyList()).map { normSender(it) }.toSet()
        fun hasReplyFrom(list: List<ReplyItem>, names: Set<String>): Boolean =
            list.any { normSender(it.sender) in names }
        fun hasReactedReplyFrom(list: List<ReplyItem>, names: Set<String>): Boolean =
            list.any { normSender(it.sender) in names && it.reactionEmoji.isNotEmpty() }
        val ordersByIndex = LinkedHashMap<Int, OrderExtractor.BatchOrder>()
        val loadingIndices = mutableSetOf<Int>()
        val analyzedIndices = mutableSetOf<Int>()
        val savedIndices = mutableSetOf<Int>()
        val savedOrderInfo = mutableMapOf<Int, Pair<Long, Boolean>>() // idx -> (orderId, paid)
        var currentChatName = ""
        var currentDate = ""
        var saveTotal = 0
        var saveDone = 0
        var saveFailed = 0

        fun updateSaveButton() {
            if (saveTotal > 0 && saveDone < saveTotal) {
                btnSaveAll.text = ""
                pbSaveAll.visibility = View.VISIBLE
                btnSaveAll.isEnabled = false
            } else {
                btnSaveAll.text = "3. Lưu đơn"
                pbSaveAll.visibility = View.GONE
            }
        }

        fun updateStatus() {
            val totalOrders = ordersByIndex.size
            val matched = ordersByIndex.values.count { it.matched }
            val pending = (texts.indices).count { it !in analyzedIndices && it !in loadingIndices }
            val loading = loadingIndices.size
            val parts = mutableListOf("$currentChatName · $currentDate · ${texts.size} tin")
            parts += "$totalOrders đơn ($matched khớp)"
            if (loading > 0) parts += "đang phân tích $loading"
            if (pending > 0 && loading == 0) parts += "$pending chưa phân tích"
            tvStatus.text = parts.joinToString(" · ")
        }

        lateinit var rerender: () -> Unit
        lateinit var analyzeOne: (Int) -> Unit
        lateinit var analyzeMany: (List<Int>) -> Unit
        lateinit var saveOne: (Int) -> Unit
        lateinit var pickCandidate: (Int, ZaloChat) -> Unit
        lateinit var checkAlreadySaved: (Int) -> Unit
        lateinit var togglePaid: (Int) -> Unit

        pickCandidate = { idx, zc ->
            ordersByIndex[idx]?.let { ord ->
                ordersByIndex[idx] = ord.copy(
                    zaloId = zc.zaloId,
                    matched = true,
                    avatarUrl = zc.avatarUrl,
                    ambiguous = false,
                    candidates = emptyList()
                )
                rerender()
                checkAlreadySaved(idx)
            }
        }

        fun styleFilterChip(v: TextView, on: Boolean) {
            v.background = androidx.core.content.ContextCompat.getDrawable(
                this, if (on) R.drawable.bg_btn_outline else R.drawable.bg_input_field
            )
            v.setTextColor(if (on) 0xFF1E88E5.toInt() else 0xFF90A4AE.toInt())
            v.setTypeface(v.typeface, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        fun visibleIndices(): List<Int> {
            val q = searchQuery.trim().lowercase(java.util.Locale.ROOT)
            return texts.indices.filter { i ->
                if (q.isNotEmpty()) {
                    if (!texts[i].lowercase(java.util.Locale.ROOT).contains(q)) return@filter false
                }
                if (filterUnmapped) {
                    val ord = ordersByIndex[i]
                    if (ord != null && ord.matched) return@filter false
                }
                if (filterUnsaved) {
                    if (i in savedIndices) return@filter false
                }
                if (filterUnpaid) {
                    if (savedOrderInfo[i]?.second == true) return@filter false
                }
                if (filterConfirmed) {
                    if (confirmed.getOrNull(i) != true) return@filter false
                }
                if (filterUnconfirmed) {
                    if (confirmed.getOrNull(i) == true) return@filter false
                }
                val rList = replies.getOrNull(i) ?: emptyList()
                if (filterFreeship) {
                    if (!hasReplyFrom(rList, freeshipSenders)) return@filter false
                }
                if (filterDone) {
                    if (!hasReplyFrom(rList, doneSenders)) return@filter false
                }
                if (filterBooked) {
                    if (!hasReactedReplyFrom(rList, doneSenders)) return@filter false
                }
                true
            }
        }

        rerender = {
            styleFilterChip(btnFilterUnmapped, filterUnmapped)
            styleFilterChip(btnFilterUnsaved, filterUnsaved)
            styleFilterChip(btnFilterUnpaid, filterUnpaid)
            styleFilterChip(btnFilterConfirmed, filterConfirmed)
            styleFilterChip(btnFilterUnconfirmed, filterUnconfirmed)
            styleFilterChip(btnFilterFreeship, filterFreeship)
            styleFilterChip(btnFilterDone, filterDone)
            styleFilterChip(btnFilterBooked, filterBooked)
            renderPairedRows(
                messagesContainer, texts, timesMs, replies, confirmed, visibleIndices(),
                ordersByIndex, loadingIndices, analyzedIndices, savedIndices, savedOrderInfo,
                analyzeOne, saveOne, pickCandidate, togglePaid
            )
            if (analyzeCancel != null) {
                btnAnalyze.text = "Huỷ phân tích"
                btnAnalyze.isEnabled = true
            } else {
                btnAnalyze.text = analyzeBtnDefaultText
                btnAnalyze.isEnabled = texts.isNotEmpty() &&
                    (texts.indices).any { it !in analyzedIndices && it !in loadingIndices }
            }
            btnSaveAll.isEnabled = saveTotal == 0 && ordersByIndex.values.any {
                it.matched && it.messageIndex !in savedIndices && it.items.isNotEmpty()
            }
            updateSaveButton()
            updateStatus()
        }

        val isoDateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }

        checkAlreadySaved = { idx ->
            val ord = ordersByIndex[idx]
            if (ord != null && ord.matched && ord.zaloId.isNotBlank() && ord.items.isNotEmpty()
                && idx !in savedIndices) {
                val tMs = timesMs.getOrNull(idx) ?: 0L
                val orderDate = isoDateFmt.format(
                    java.util.Date(if (tMs > 0) tMs else System.currentTimeMillis())
                )
                val total = ord.items.sumOf { it.lineTotal }
                val code = OrderRecord.makeCode(ord.zaloId, orderDate, total)
                ioExecutor.execute {
                    val existing = runCatching {
                        ShopDb(this).getOrderPaidByCode(code)
                    }.getOrNull()
                    if (existing != null) {
                        mainHandler.post {
                            savedIndices.add(idx)
                            savedOrderInfo[idx] = existing
                            rerender()
                        }
                    }
                }
            }
        }

        togglePaid = { idx ->
            val info = savedOrderInfo[idx]
            if (info != null) {
                val newPaid = !info.second
                savedOrderInfo[idx] = info.first to newPaid
                rerender()
                ioExecutor.execute {
                    runCatching { ShopDb(this).setOrderPaid(info.first, newPaid) }
                    runCatching { OrderExtractor.onOrderSaved?.invoke() }
                }
            }
        }

        saveOne = { idx ->
            val ord = ordersByIndex[idx]
            val tMs = timesMs.getOrNull(idx) ?: 0L
            when {
                ord == null -> Unit
                !ord.matched -> showErrorToast("Đơn chưa khớp Zalo, không thể lưu")
                ord.items.isEmpty() -> showErrorToast("Đơn không có món")
                idx in savedIndices -> Unit
                else -> {
                    saveTotal++
                    updateSaveButton()
                    val orderDate = isoDateFmt.format(java.util.Date(if (tMs > 0) tMs else System.currentTimeMillis()))
                    val total = ord.items.sumOf { it.lineTotal }
                    val record = OrderRecord(
                        createdAt = if (tMs > 0) tMs else System.currentTimeMillis(),
                        orderDate = orderDate,
                        convName = currentChatName,
                        senderName = ord.customerName.trim(),
                        phone = ord.phone.trim(),
                        address = ord.address.trim(),
                        itemsText = ord.items.joinToString("\n") { oi ->
                            val q = if (oi.quantity == oi.quantity.toLong().toDouble())
                                oi.quantity.toLong().toString() else oi.quantity.toString()
                            "$q × ${oi.productName}" + if (oi.note.isNotBlank()) " (${oi.note})" else ""
                        },
                        rawJson = ord.rawJson,
                        totalAmount = total,
                        note = ord.orderNote.trim(),
                        zaloId = ord.zaloId,
                        orderCode = OrderRecord.makeCode(ord.zaloId, orderDate, total)
                    )
                    val proceedSave: (Long?, Boolean) -> Unit = { updateExistingId, keepPaid ->
                        ioExecutor.execute {
                            val res = runCatching {
                                val db = ShopDb(this)
                                val outcome: Pair<Long, Boolean> = if (updateExistingId != null) {
                                    db.updateOrder(updateExistingId, record.copy(paid = keepPaid), ord.items)
                                    updateExistingId to false
                                } else {
                                    db.insertOrderWithDedup(record, ord.items)
                                }
                                runCatching { db.markAsCustomer(ord.zaloId, ord.phone, ord.address) }
                                outcome
                            }
                            mainHandler.post {
                                try {
                                    res.onSuccess { (id, isNew) ->
                                        savedIndices.add(idx)
                                        val paidNow = if (updateExistingId != null) keepPaid
                                            else if (isNew) false
                                            else runCatching { ShopDb(this).getOrderPaidByCode(record.orderCode)?.second }
                                                .getOrNull() ?: false
                                        savedOrderInfo[idx] = id to paidNow
                                        if (updateExistingId != null) {
                                            showSuccessToast("Đã cập nhật đơn #$id (${record.orderCode})")
                                        } else if (isNew) {
                                            showSuccessToast("Đã lưu đơn #$id (${record.orderCode})")
                                        } else {
                                            showErrorToast("Đơn đã tồn tại #$id (${record.orderCode})")
                                        }
                                        runCatching { OrderExtractor.onOrderSaved?.invoke() }
                                    }.onFailure { e ->
                                        Log.e(TAG, "save fail idx=$idx", e)
                                        saveFailed++
                                        showErrorToast("Lỗi lưu đơn #$idx: ${e.message ?: "?"}")
                                    }
                                } finally {
                                    saveDone++
                                    Log.d(TAG, "save progress $saveDone/$saveTotal (failed=$saveFailed)")
                                    if (saveDone >= saveTotal) {
                                        val ok = saveDone - saveFailed
                                        if (saveFailed > 0) {
                                            showErrorToast("Đã lưu $ok/$saveDone đơn · $saveFailed lỗi")
                                        } else if (saveDone > 1) {
                                            showSuccessToast("Đã lưu xong $saveDone đơn")
                                        }
                                        saveTotal = 0
                                        saveDone = 0
                                        saveFailed = 0
                                    }
                                    rerender()
                                }
                            }
                        }
                    }

                    val phoneForCheck = record.phone
                    if (phoneForCheck.isBlank()) {
                        proceedSave(null, false)
                    } else {
                        ioExecutor.execute {
                            val dups = runCatching {
                                ShopDb(this).findOrdersByPhoneOnDate(phoneForCheck, orderDate)
                            }.getOrElse { emptyList() }
                            mainHandler.post {
                                if (dups.isEmpty()) {
                                    proceedSave(null, false)
                                } else {
                                    DuplicateOrderDialog.show(
                                        ctx = this,
                                        newCustomerName = record.senderName,
                                        newPhone = phoneForCheck,
                                        existing = dups,
                                        onCreateNew = { proceedSave(null, false) },
                                        onUpdateExisting = { oldId ->
                                            val keep = dups.firstOrNull { it.id == oldId }?.paid ?: false
                                            proceedSave(oldId, keep)
                                        },
                                        onCancel = {
                                            saveTotal--
                                            updateSaveButton()
                                            if (saveTotal > 0 && saveDone >= saveTotal) {
                                                val ok = saveDone - saveFailed
                                                if (saveFailed > 0) {
                                                    showErrorToast("Đã lưu $ok/$saveDone đơn · $saveFailed lỗi")
                                                } else if (saveDone > 1) {
                                                    showSuccessToast("Đã lưu xong $saveDone đơn")
                                                }
                                                saveTotal = 0
                                                saveDone = 0
                                                saveFailed = 0
                                            }
                                            rerender()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        analyzeOne = { idx ->
            if (idx in 0 until texts.size && idx !in loadingIndices) {
                loadingIndices.add(idx)
                ordersByIndex.remove(idx)
                savedIndices.remove(idx)
                rerender()
                val input = org.json.JSONArray()
                    .put(org.json.JSONObject().put("index", idx).put("text", texts[idx]))
                    .toString()
                OrderExtractor.analyzeBatchOrders(this, input, onProgress = null) { result ->
                    loadingIndices.remove(idx)
                    analyzedIndices.add(idx)
                    result.onSuccess { orders ->
                        orders.firstOrNull { it.messageIndex == idx }?.let {
                            ordersByIndex[idx] = it
                        }
                    }.onFailure { e ->
                        Log.e(TAG, "analyzeOne fail idx=$idx", e)
                        analyzedIndices.remove(idx)
                        Toast.makeText(this, "Lỗi AI: ${e.message ?: "?"}", Toast.LENGTH_SHORT).show()
                    }
                    rerender()
                    checkAlreadySaved(idx)
                }
            }
        }

        analyzeMany = { indices ->
            if (indices.isEmpty()) {
                Toast.makeText(this, "Không còn tin nào cần phân tích", Toast.LENGTH_SHORT).show()
            } else {
                indices.forEach {
                    loadingIndices.add(it)
                    ordersByIndex.remove(it)
                    savedIndices.remove(it)
                }
                rerender()
                val input = org.json.JSONArray().apply {
                    indices.forEach { i ->
                        put(org.json.JSONObject().put("index", i).put("text", texts[i]))
                    }
                }.toString()
                val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
                analyzeCancel = cancelFlag
                rerender()
                OrderExtractor.analyzeBatchOrders(
                    this, input,
                    cancel = cancelFlag,
                    onProgress = { partial, done, totalChunks ->
                        val doneIndices = indices.take(done * 10)
                        doneIndices.forEach {
                            loadingIndices.remove(it)
                            analyzedIndices.add(it)
                        }
                        partial.forEach { ord ->
                            if (ord.messageIndex in doneIndices) ordersByIndex[ord.messageIndex] = ord
                        }
                        tvStatus.text = "$currentChatName · $currentDate · ${texts.size} tin · AI $done/$totalChunks"
                        rerender()
                        partial.forEach { ord ->
                            if (ord.messageIndex in doneIndices) checkAlreadySaved(ord.messageIndex)
                        }
                    }
                ) { result ->
                    indices.forEach { loadingIndices.remove(it) }
                    analyzeCancel = null
                    result.onSuccess { orders ->
                        indices.forEach { analyzedIndices.add(it) }
                        orders.forEach { ord ->
                            if (ord.messageIndex in indices) ordersByIndex[ord.messageIndex] = ord
                        }
                    }.onFailure { e ->
                        if (e is OrderExtractor.AnalyzeCancelledException) {
                            Log.i(TAG, "analyzeBatchOrders cancelled")
                            Toast.makeText(this, "Đã huỷ phân tích", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.e(TAG, "analyzeBatchOrders fail", e)
                            Toast.makeText(this, "Lỗi AI: ${e.message ?: "?"}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    rerender()
                    indices.forEach { checkAlreadySaved(it) }
                }
            }
        }

        btnFilterUnmapped.setOnClickListener {
            filterUnmapped = !filterUnmapped
            rerender()
        }
        btnFilterUnsaved.setOnClickListener {
            filterUnsaved = !filterUnsaved
            rerender()
        }
        btnFilterUnpaid.setOnClickListener {
            filterUnpaid = !filterUnpaid
            rerender()
        }
        btnFilterConfirmed.setOnClickListener {
            filterConfirmed = !filterConfirmed
            if (filterConfirmed) filterUnconfirmed = false
            rerender()
        }
        btnFilterUnconfirmed.setOnClickListener {
            filterUnconfirmed = !filterUnconfirmed
            if (filterUnconfirmed) filterConfirmed = false
            rerender()
        }
        btnFilterFreeship.setOnClickListener {
            filterFreeship = !filterFreeship
            rerender()
        }
        btnFilterDone.setOnClickListener {
            filterDone = !filterDone
            rerender()
        }
        btnFilterBooked.setOnClickListener {
            filterBooked = !filterBooked
            rerender()
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString() ?: ""
                rerender()
            }
        })
        styleFilterChip(btnFilterUnmapped, filterUnmapped)
        styleFilterChip(btnFilterUnsaved, filterUnsaved)
        styleFilterChip(btnFilterUnpaid, filterUnpaid)
        styleFilterChip(btnFilterConfirmed, filterConfirmed)
        styleFilterChip(btnFilterUnconfirmed, filterUnconfirmed)
        styleFilterChip(btnFilterFreeship, filterFreeship)
        styleFilterChip(btnFilterDone, filterDone)
        styleFilterChip(btnFilterBooked, filterBooked)

        btnRead.setOnClickListener {
            val pos = spChat.selectedItemPosition
            if (pos < 0 || pos >= chats.size) return@setOnClickListener
            val chat = chats[pos]
            val zaloId = chat.zaloId
            val pickedDate = tvDate.text.toString()
            val tz = java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
            val dayStart = (java.util.Calendar.getInstance(tz).apply {
                timeInMillis = cal.timeInMillis
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }).timeInMillis
            val dayEnd = dayStart + 24L * 60L * 60L * 1000L - 1L
            currentChatName = chat.name.ifBlank { zaloId }
            currentDate = pickedDate
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Đang đọc tin nhắn của $currentChatName..."
            tvMessagesLabel.visibility = View.GONE
            messagesContainer.removeAllViews()
            texts = emptyList(); timesMs = emptyList(); replies = emptyList(); confirmed = emptyList()
            ordersByIndex.clear(); loadingIndices.clear(); analyzedIndices.clear()
            savedIndices.clear()
            btnRead.isEnabled = false
            btnRead.text = ""
            pbRead.visibility = View.VISIBLE
            btnAnalyze.isEnabled = false

            checkoutCallback = cb@{ animId, status, json ->
                btnRead.isEnabled = true
                btnRead.text = "1. Đọc tin nhắn"
                pbRead.visibility = View.GONE
                if (animId != zaloId) return@cb
                when (status) {
                    "OK" -> {
                        val arr = runCatching { org.json.JSONArray(json) }.getOrNull()
                        if (arr == null || arr.length() == 0) {
                            tvMessagesLabel.visibility = View.GONE
                            tvStatus.text = "Không có tin nhắn nào trong hội thoại."
                            return@cb
                        }
                        val newTexts = ArrayList<String>(arr.length())
                        val newTimesMs = ArrayList<Long>(arr.length())
                        val newReplies = ArrayList<List<ReplyItem>>(arr.length())
                        val newConfirmed = ArrayList<Boolean>(arr.length())
                        for (k in 0 until arr.length()) {
                            val o = arr.optJSONObject(k) ?: continue
                            newTexts.add(o.optString("text"))
                            newTimesMs.add(o.optString("time").toLongOrNull() ?: 0L)
                            newConfirmed.add(o.optBoolean("confirmed", false))
                            val rArr = o.optJSONArray("replies")
                            val list = ArrayList<ReplyItem>()
                            if (rArr != null) {
                                for (m in 0 until rArr.length()) {
                                    val ro = rArr.optJSONObject(m) ?: continue
                                    val rs = ro.optString("sender")
                                    val rt = ro.optString("text")
                                    val rtm = ro.optString("time").toLongOrNull() ?: 0L
                                    val re = ro.optString("reactionEmoji")
                                    val rc = ro.optInt("reactionCount", 0)
                                    if (rt.isNotEmpty()) list.add(ReplyItem(rs, rt, rtm, re, rc))
                                }
                            }
                            list.sortBy { it.timeMs }
                            newReplies.add(list)
                        }
                        texts = newTexts
                        timesMs = newTimesMs
                        replies = newReplies
                        confirmed = newConfirmed
                        tvMessagesLabel.visibility = View.VISIBLE
                        tvMessagesLabel.text = "Tin nhắn  /  Đơn hàng AI"
                        etSearch.visibility = View.VISIBLE
                        filterScroll.visibility = View.VISIBLE
                        rerender()
                    }
                    "NOT_FOUND" -> {
                        tvMessagesLabel.visibility = View.GONE
                        tvStatus.text = "Không tìm thấy hội thoại trong sidebar (cuộn sidebar Zalo để load)."
                    }
                    "NO_ID" -> {
                        tvMessagesLabel.visibility = View.GONE
                        tvStatus.text = "Thiếu zalo id."
                    }
                    else -> {
                        tvMessagesLabel.visibility = View.GONE
                        tvStatus.text = "Lỗi: $status"
                    }
                }
            }

            val safeId = org.json.JSONObject.quote(zaloId)
            webView.evaluateJavascript(
                "window.__autoOrderFetchById && window.__autoOrderFetchById($safeId, $dayStart, $dayEnd);",
                null
            )
        }

        btnAnalyze.setOnClickListener {
            val running = analyzeCancel
            if (running != null) {
                running.set(true)
                btnAnalyze.isEnabled = false
                btnAnalyze.text = "Đang huỷ..."
                Toast.makeText(this, "Huỷ sau khi xong chunk hiện tại", Toast.LENGTH_SHORT).show()
            } else {
                val pending = (texts.indices).filter {
                    it !in analyzedIndices && it !in loadingIndices
                }
                analyzeMany(pending)
            }
        }

        btnSaveAll.setOnClickListener {
            val toSave = ordersByIndex.values.filter {
                it.matched && it.messageIndex !in savedIndices && it.items.isNotEmpty()
            }.map { it.messageIndex }
            if (toSave.isEmpty()) {
                showErrorToast("Không có đơn nào để lưu")
            } else {
                toSave.forEach { saveOne(it) }
            }
        }

        dialog.setOnDismissListener {
            checkoutCallback = null
            if (checkoutDialog === dialog) {
                checkoutDialog = null
                setBottomBarItemActive(R.id.btnCheckout, false)
            }
        }
        dialog.show()
    }

    private fun renderPairedRows(
        container: android.widget.LinearLayout,
        texts: List<String>,
        timesMs: List<Long>,
        replies: List<List<ReplyItem>>,
        confirmed: List<Boolean>,
        visibleIndices: List<Int>,
        ordersByIndex: Map<Int, OrderExtractor.BatchOrder>,
        loadingIndices: Set<Int>,
        analyzedIndices: Set<Int>,
        savedIndices: Set<Int>,
        savedOrderInfo: Map<Int, Pair<Long, Boolean>>,
        onAnalyzeOne: (Int) -> Unit,
        onSaveOne: (Int) -> Unit,
        onPickCandidate: (Int, ZaloChat) -> Unit,
        onTogglePaid: (Int) -> Unit
    ) {
        container.removeAllViews()
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
        }
        val density = resources.displayMetrics.density
        val padH = (10 * density).toInt()
        val padV = (8 * density).toInt()
        val marginV = (6 * density).toInt()
        val gap = (8 * density).toInt()

        container.clipChildren = false
        container.clipToPadding = false
        for (i in visibleIndices) {
            val row = android.widget.LinearLayout(this)
            row.orientation = android.widget.LinearLayout.HORIZONTAL
            row.clipChildren = false
            row.clipToPadding = false
            row.weightSum = 7f
            val rowLp = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rowLp.topMargin = marginV
            rowLp.bottomMargin = marginV
            row.layoutParams = rowLp

            // LEFT: message bubble (weight 3/7)
            val leftCol = android.widget.LinearLayout(this)
            leftCol.orientation = android.widget.LinearLayout.VERTICAL
            val leftLp = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f
            )
            leftLp.marginEnd = gap / 2
            leftCol.layoutParams = leftLp

            val bubble = TextView(this)
            bubble.text = texts[i]
            bubble.setPadding(padH, padV, padH, padV)
            bubble.textSize = 12f
            bubble.setTextColor(0xFFFFFFFF.toInt())
            bubble.background = androidx.core.content.ContextCompat.getDrawable(
                this, R.drawable.bg_btn_primary
            )
            leftCol.addView(bubble, android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            val tMs = timesMs.getOrNull(i) ?: 0L
            if (tMs > 0L) {
                val tvTime = TextView(this)
                tvTime.text = timeFmt.format(java.util.Date(tMs))
                tvTime.textSize = 10f
                tvTime.setTextColor(0xFF90A4AE.toInt())
                val ttLp = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                ttLp.topMargin = (2 * density).toInt()
                tvTime.layoutParams = ttLp
                leftCol.addView(tvTime)
            }

            // MIDDLE: replies quoting this me-message (weight 1/7 = 1/3 of leftCol)
            val replyCol = android.widget.LinearLayout(this)
            replyCol.orientation = android.widget.LinearLayout.VERTICAL
            replyCol.clipChildren = false
            replyCol.clipToPadding = false
            val replyLp = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            replyLp.marginStart = gap / 2
            replyLp.marginEnd = gap / 2
            replyCol.layoutParams = replyLp

            if (confirmed.getOrNull(i) == true) {
                val tvConfirm = TextView(this)
                tvConfirm.text = "✓ Đã xác nhận"
                tvConfirm.textSize = 11f
                tvConfirm.setTextColor(0xFF2E7D32.toInt())
                tvConfirm.setTypeface(tvConfirm.typeface, android.graphics.Typeface.BOLD)
                tvConfirm.setPadding(padH, padV, padH, padV)
                tvConfirm.background = androidx.core.content.ContextCompat.getDrawable(
                    this, R.drawable.bg_input_field
                )
                val cLp = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                cLp.bottomMargin = (4 * density).toInt()
                tvConfirm.layoutParams = cLp
                replyCol.addView(tvConfirm)
            }

            val rList = replies.getOrNull(i) ?: emptyList()
            for (rep in rList) {
                if (rep.sender.isNotBlank()) {
                    val tvSender = TextView(this)
                    tvSender.text = rep.sender
                    tvSender.textSize = 9f
                    tvSender.setTextColor(0xFF1E88E5.toInt())
                    tvSender.setTypeface(tvSender.typeface, android.graphics.Typeface.BOLD)
                    val sLp = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    sLp.topMargin = (2 * density).toInt()
                    tvSender.layoutParams = sLp
                    replyCol.addView(tvSender)
                }

                val bubbleFrame = android.widget.FrameLayout(this)
                bubbleFrame.clipChildren = false
                bubbleFrame.clipToPadding = false
                val bubbleFrameLp = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                bubbleFrameLp.bottomMargin = (10 * density).toInt()
                bubbleFrame.layoutParams = bubbleFrameLp

                val rb = TextView(this)
                rb.text = rep.text
                val extraBottom = if (rep.reactionEmoji.isNotEmpty()) (10 * density).toInt() else 0
                rb.setPadding(padH, padV, padH, padV + extraBottom)
                rb.textSize = 11f
                rb.setTextColor(0xFF263238.toInt())
                rb.background = androidx.core.content.ContextCompat.getDrawable(
                    this, R.drawable.bg_input_field
                )
                bubbleFrame.addView(rb, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))

                if (rep.reactionEmoji.isNotEmpty()) {
                    val emoji = mapZaloEmoji(rep.reactionEmoji)
                    val countText = if (rep.reactionCount > 1) " ${rep.reactionCount}" else ""
                    val tvReact = TextView(this)
                    tvReact.text = "$emoji$countText"
                    tvReact.textSize = 10f
                    tvReact.setTextColor(0xFF455A64.toInt())
                    tvReact.setPadding(
                        (6 * density).toInt(), (1 * density).toInt(),
                        (6 * density).toInt(), (1 * density).toInt()
                    )
                    val pillBg = android.graphics.drawable.GradientDrawable()
                    pillBg.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    pillBg.cornerRadius = 16 * density
                    pillBg.setColor(0xFFFFFFFF.toInt())
                    pillBg.setStroke((1 * density).toInt(), 0xFFE0E0E0.toInt())
                    tvReact.background = pillBg
                    tvReact.elevation = 6 * density
                    tvReact.translationZ = 6 * density
                    val rLp = android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM or android.view.Gravity.END
                    )
                    rLp.rightMargin = (6 * density).toInt()
                    rLp.bottomMargin = (-8 * density).toInt()
                    tvReact.layoutParams = rLp
                    bubbleFrame.addView(tvReact)
                }
                replyCol.addView(bubbleFrame)

                if (rep.timeMs > 0L) {
                    val tvT = TextView(this)
                    tvT.text = timeFmt.format(java.util.Date(rep.timeMs))
                    tvT.textSize = 9f
                    tvT.setTextColor(0xFF90A4AE.toInt())
                    val ttLp = android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    ttLp.bottomMargin = (4 * density).toInt()
                    tvT.layoutParams = ttLp
                    replyCol.addView(tvT)
                }
            }

            // RIGHT: order card or placeholder (weight 3/7)
            val rightCol = android.widget.LinearLayout(this)
            rightCol.orientation = android.widget.LinearLayout.VERTICAL
            val rightLp = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f
            )
            rightLp.marginStart = gap / 2
            rightCol.layoutParams = rightLp

            val isLoading = i in loadingIndices
            val isAnalyzed = i in analyzedIndices
            val order = ordersByIndex[i]
            when {
                isLoading -> rightCol.addView(buildLoadingCard(padH, padV, density))
                isAnalyzed && order != null -> {
                    rightCol.addView(buildOrderCard(
                        order, i, i in savedIndices, savedOrderInfo[i]?.second ?: false,
                        padH, padV, density, onAnalyzeOne, onSaveOne, onPickCandidate, onTogglePaid
                    ))
                }
                isAnalyzed -> {
                    rightCol.addView(buildNotOrderCard(i, padH, padV, density, onAnalyzeOne))
                }
                else -> rightCol.addView(buildAnalyzeButton(i, "Phân tích", density, onAnalyzeOne))
            }

            row.addView(leftCol)
            row.addView(replyCol)
            row.addView(rightCol)
            container.addView(row)
        }
    }

    private fun buildAnalyzeButton(
        index: Int,
        text: String,
        density: Float,
        onAnalyzeOne: (Int) -> Unit
    ): View {
        val btn = android.widget.Button(this)
        btn.text = text
        btn.textSize = 11f
        btn.isAllCaps = false
        btn.setTextColor(0xFF1E88E5.toInt())
        btn.background = androidx.core.content.ContextCompat.getDrawable(
            this, R.drawable.bg_btn_outline
        )
        val padH = (10 * density).toInt()
        val padV = (4 * density).toInt()
        btn.setPadding(padH, padV, padH, padV)
        btn.minHeight = (32 * density).toInt()
        btn.minimumHeight = (32 * density).toInt()
        val lp = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (32 * density).toInt()
        )
        lp.topMargin = (4 * density).toInt()
        btn.layoutParams = lp
        btn.setOnClickListener { onAnalyzeOne(index) }
        return btn
    }

    private fun buildLoadingCard(padH: Int, padV: Int, density: Float): View {
        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(padH, padV, padH, padV)
        row.background = androidx.core.content.ContextCompat.getDrawable(
            this, R.drawable.bg_input_field
        )
        val rowLp = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.bottomMargin = (4 * density).toInt()
        row.layoutParams = rowLp

        val spinnerSize = (14 * density).toInt()
        val spinner = android.widget.ProgressBar(this)
        spinner.isIndeterminate = true
        val spinnerLp = android.widget.LinearLayout.LayoutParams(spinnerSize, spinnerSize)
        spinnerLp.marginEnd = (8 * density).toInt()
        spinner.layoutParams = spinnerLp
        row.addView(spinner)

        val tv = TextView(this)
        tv.text = "Đang phân tích đơn hàng..."
        tv.textSize = 11f
        tv.setTextColor(0xFF1E88E5.toInt())
        row.addView(tv)
        return row
    }

    private fun buildNotOrderCard(
        index: Int, padH: Int, padV: Int, density: Float,
        onAnalyzeOne: (Int) -> Unit
    ): View {
        val frame = android.widget.FrameLayout(this)
        val frameLp = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        frameLp.bottomMargin = (4 * density).toInt()
        frame.layoutParams = frameLp

        val tv = TextView(this)
        tv.text = "(không phải đơn)"
        tv.setPadding(padH, padV, (padH + 32 * density).toInt(), padV)
        tv.textSize = 11f
        tv.setTextColor(0xFF90A4AE.toInt())
        tv.background = androidx.core.content.ContextCompat.getDrawable(
            this, R.drawable.bg_input_field
        )
        frame.addView(tv, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        frame.addView(buildCornerIconButton(R.drawable.ic_refresh, 0xFF1E88E5.toInt(), density) {
            onAnalyzeOne(index)
        })
        return frame
    }

    private fun buildCornerIconButton(
        iconRes: Int, tintColor: Int, density: Float,
        onClick: () -> Unit
    ): View {
        val size = (28 * density).toInt()
        val iv = android.widget.ImageView(this)
        iv.setImageResource(iconRes)
        iv.setColorFilter(tintColor)
        val pad = (5 * density).toInt()
        iv.setPadding(pad, pad, pad, pad)
        iv.background = androidx.core.content.ContextCompat.getDrawable(
            this, android.R.drawable.btn_default
        )?.mutate()
        iv.isClickable = true
        iv.isFocusable = true
        iv.setOnClickListener { onClick() }
        val lp = android.widget.FrameLayout.LayoutParams(
            size, size,
            android.view.Gravity.TOP or android.view.Gravity.END
        )
        lp.topMargin = (4 * density).toInt()
        lp.rightMargin = (12 * density).toInt()
        iv.layoutParams = lp
        return iv
    }

    private fun buildOrderCard(
        ord: OrderExtractor.BatchOrder,
        index: Int,
        isSaved: Boolean,
        isPaid: Boolean,
        padH: Int, padV: Int, density: Float,
        onAnalyzeOne: (Int) -> Unit,
        onSaveOne: (Int) -> Unit,
        onPickCandidate: (Int, ZaloChat) -> Unit,
        onTogglePaid: (Int) -> Unit
    ): View {
        val frame = android.widget.FrameLayout(this)
        val frameLp = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        frameLp.bottomMargin = (4 * density).toInt()
        frame.layoutParams = frameLp

        val card = android.widget.LinearLayout(this)
        card.orientation = android.widget.LinearLayout.VERTICAL
        // extra right padding so corner icons don't overlap content
        val rightPad = (padH + 64 * density).toInt()
        card.setPadding(padH, padV, rightPad, padV)
        card.background = androidx.core.content.ContextCompat.getDrawable(
            this, R.drawable.bg_input_field
        )
        frame.addView(card, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val priceFmt = java.text.NumberFormat.getInstance(java.util.Locale("vi", "VN"))

        val nameLabel = ord.customerName.ifBlank { "(không tên)" }
        if (ord.matched) {
            val header = android.widget.LinearLayout(this)
            header.orientation = android.widget.LinearLayout.HORIZONTAL
            header.gravity = android.view.Gravity.CENTER_VERTICAL

            val avatarSize = (40 * density).toInt()
            val avatar = android.widget.ImageView(this)
            val avLp = android.widget.LinearLayout.LayoutParams(avatarSize, avatarSize)
            avLp.marginEnd = (8 * density).toInt()
            avatar.layoutParams = avLp
            avatar.setBackgroundResource(R.drawable.bg_avatar_placeholder)
            if (ord.avatarUrl.isNotBlank()) {
                avatar.load(ord.avatarUrl) {
                    crossfade(true)
                    placeholder(R.drawable.bg_avatar_placeholder)
                    error(R.drawable.bg_avatar_placeholder)
                    transformations(coil.transform.CircleCropTransformation())
                }
            }
            header.addView(avatar)

            val nameCol = android.widget.LinearLayout(this)
            nameCol.orientation = android.widget.LinearLayout.VERTICAL
            val nameColLp = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            nameCol.layoutParams = nameColLp

            val titleRow = android.widget.LinearLayout(this)
            titleRow.orientation = android.widget.LinearLayout.HORIZONTAL
            titleRow.gravity = android.view.Gravity.CENTER_VERTICAL

            val title = TextView(this)
            title.text = nameLabel
            title.setTextColor(0xFF1E88E5.toInt())
            title.textSize = 13f
            title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            title.paintFlags = title.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            title.isClickable = true
            title.isFocusable = true
            title.setOnClickListener {
                OrderExtractor.pickZaloChat(this, "Đổi khách hàng cho \"$nameLabel\"") { zc ->
                    onPickCandidate(index, zc)
                }
            }
            val titleLp = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            title.layoutParams = titleLp
            titleRow.addView(title)

            titleRow.addView(buildCopyIcon(
                R.drawable.ic_copy, 0xFF90A4AE.toInt(), density, "Tên Zalo", ord.customerName,
                searchMode = "name"
            ))
            if (ord.phone.isNotBlank()) {
                titleRow.addView(buildCopyIcon(
                    R.drawable.ic_phone_android, 0xFF1E88E5.toInt(), density, "SĐT", ord.phone,
                    searchMode = "phone"
                ))
            }

            nameCol.addView(titleRow)

            val zid = TextView(this)
            zid.text = ord.zaloId
            zid.textSize = 9f
            zid.setTextColor(0xFF90A4AE.toInt())
            zid.maxLines = 1
            zid.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            nameCol.addView(zid)

            header.addView(nameCol)
            card.addView(header)
        } else {
            val titleRow = android.widget.LinearLayout(this)
            titleRow.orientation = android.widget.LinearLayout.HORIZONTAL
            titleRow.gravity = android.view.Gravity.CENTER_VERTICAL

            val title = TextView(this)
            title.text = "⚠ $nameLabel"
            title.setTextColor(0xFFEF6C00.toInt())
            title.textSize = 12f
            title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            title.paintFlags = title.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            title.isClickable = true
            title.isFocusable = true
            title.setOnClickListener {
                OrderExtractor.pickZaloChat(this, "Map khách hàng cho \"$nameLabel\"") { zc ->
                    onPickCandidate(index, zc)
                }
            }
            val titleLp = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            title.layoutParams = titleLp
            titleRow.addView(title)

            titleRow.addView(buildCopyIcon(
                R.drawable.ic_copy, 0xFF90A4AE.toInt(), density, "Tên Zalo", ord.customerName,
                searchMode = "name"
            ))
            if (ord.phone.isNotBlank()) {
                titleRow.addView(buildCopyIcon(
                    R.drawable.ic_phone_android, 0xFF1E88E5.toInt(), density, "SĐT", ord.phone,
                    searchMode = "phone"
                ))
            }

            card.addView(titleRow)
        }

        ord.items.forEach { it ->
            val tv = TextView(this)
            val qStr = if (it.quantity == it.quantity.toLong().toDouble())
                it.quantity.toLong().toString() else it.quantity.toString()
            val priceStr = if (it.unitPrice > 0)
                "  — ${priceFmt.format(it.lineTotal)}₫" else "  — (chưa map)"
            val noteStr = if (it.note.isNotBlank()) " (${it.note})" else ""
            tv.text = "$qStr × ${it.productName}$noteStr$priceStr"
            tv.textSize = 11f
            tv.setTextColor(0xFF263238.toInt())
            card.addView(tv)
        }

        if (ord.items.isNotEmpty()) {
            val total = ord.items.sumOf { it.lineTotal }
            if (total > 0) {
                val tvTotal = TextView(this)
                tvTotal.text = "Tổng: ${priceFmt.format(total)}₫"
                tvTotal.textSize = 11f
                tvTotal.setTextColor(0xFF1E88E5.toInt())
                tvTotal.setTypeface(tvTotal.typeface, android.graphics.Typeface.BOLD)
                val totalLp = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                totalLp.topMargin = (3 * density).toInt()
                tvTotal.layoutParams = totalLp
                card.addView(tvTotal)
            }
        }

        if (ord.phone.isNotBlank() || ord.address.isNotBlank()) {
            val tvContact = TextView(this)
            val sb = StringBuilder()
            if (ord.phone.isNotBlank()) sb.append("SĐT: ").append(ord.phone)
            if (ord.address.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(" · ")
                sb.append(ord.address)
            }
            tvContact.text = sb.toString()
            tvContact.textSize = 10f
            tvContact.setTextColor(0xFF546E7A.toInt())
            card.addView(tvContact)
        }
        if (ord.orderNote.isNotBlank()) {
            val tvNote = TextView(this)
            tvNote.text = "📝 ${ord.orderNote}"
            tvNote.textSize = 10f
            tvNote.setTextColor(0xFF546E7A.toInt())
            card.addView(tvNote)
        }
        if (!ord.matched && ord.ambiguous && ord.candidates.isNotEmpty()) {
            val tvAmb = TextView(this)
            tvAmb.text = "⚠ ${ord.candidates.size} Zalo trùng tên \"${ord.customerName}\" — chọn 1:"
            tvAmb.textSize = 10f
            tvAmb.setTextColor(0xFFEF6C00.toInt())
            val ambLp = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            ambLp.topMargin = (4 * density).toInt()
            tvAmb.layoutParams = ambLp
            card.addView(tvAmb)

            val timeFmtCand = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
            }
            ord.candidates.forEach { zc ->
                val row = android.widget.LinearLayout(this)
                row.orientation = android.widget.LinearLayout.HORIZONTAL
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                row.isClickable = true
                row.isFocusable = true
                row.background = androidx.core.content.ContextCompat.getDrawable(
                    this, R.drawable.bg_btn_outline
                )
                val rowPadH = (8 * density).toInt()
                val rowPadV = (6 * density).toInt()
                row.setPadding(rowPadH, rowPadV, rowPadH, rowPadV)
                val rowLp = android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                rowLp.topMargin = (4 * density).toInt()
                row.layoutParams = rowLp

                val candAvSize = (28 * density).toInt()
                val iv = android.widget.ImageView(this)
                val ivLp = android.widget.LinearLayout.LayoutParams(candAvSize, candAvSize)
                ivLp.marginEnd = (8 * density).toInt()
                iv.layoutParams = ivLp
                iv.setBackgroundResource(R.drawable.bg_avatar_placeholder)
                if (zc.avatarUrl.isNotBlank()) {
                    iv.load(zc.avatarUrl) {
                        crossfade(true)
                        placeholder(R.drawable.bg_avatar_placeholder)
                        error(R.drawable.bg_avatar_placeholder)
                        transformations(coil.transform.CircleCropTransformation())
                    }
                }
                row.addView(iv)

                val txtCol = android.widget.LinearLayout(this)
                txtCol.orientation = android.widget.LinearLayout.VERTICAL
                txtCol.layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )

                val tvLine1 = TextView(this)
                val phones = zc.phoneList()
                val phoneStr = if (phones.isNotEmpty()) " · ${phones.joinToString(",")}" else ""
                tvLine1.text = "${zc.name}$phoneStr"
                tvLine1.textSize = 11f
                tvLine1.setTextColor(0xFF263238.toInt())
                tvLine1.setTypeface(tvLine1.typeface, android.graphics.Typeface.BOLD)
                tvLine1.maxLines = 1
                tvLine1.ellipsize = android.text.TextUtils.TruncateAt.END
                txtCol.addView(tvLine1)

                val tvLine2 = TextView(this)
                val tStr = if (zc.lastMsgAt > 0) timeFmtCand.format(java.util.Date(zc.lastMsgAt)) else "—"
                tvLine2.text = "${zc.zaloId} · $tStr"
                tvLine2.textSize = 9f
                tvLine2.setTextColor(0xFF90A4AE.toInt())
                tvLine2.maxLines = 1
                tvLine2.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                txtCol.addView(tvLine2)

                row.addView(txtCol)
                row.setOnClickListener { onPickCandidate(index, zc) }
                card.addView(row)
            }
        } else if (!ord.matched && ord.customerName.isNotBlank()) {
            val tvWarn = TextView(this)
            tvWarn.text = "⚠ Không tìm thấy zaloId cho \"${ord.customerName}\""
            tvWarn.textSize = 10f
            tvWarn.setTextColor(0xFFEF6C00.toInt())
            card.addView(tvWarn)
        }

        // Corner icons: retry (top), save (below) — stacked vertically in top-right
        val iconCol = android.widget.LinearLayout(this)
        iconCol.orientation = android.widget.LinearLayout.VERTICAL
        val iconColLp = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP or android.view.Gravity.END
        )
        iconColLp.topMargin = (4 * density).toInt()
        iconColLp.rightMargin = (12 * density).toInt()
        iconCol.layoutParams = iconColLp

        val size = (28 * density).toInt()
        fun mkIcon(iconRes: Int, tint: Int, enabled: Boolean = true, onClick: () -> Unit): android.widget.ImageView {
            val iv = android.widget.ImageView(this)
            iv.setImageResource(iconRes)
            iv.setColorFilter(tint)
            val pad = (5 * density).toInt()
            iv.setPadding(pad, pad, pad, pad)
            iv.background = androidx.core.content.ContextCompat.getDrawable(
                this, R.drawable.bg_input_field
            )
            iv.isClickable = enabled
            iv.isFocusable = enabled
            iv.alpha = if (enabled) 1f else 0.4f
            if (enabled) iv.setOnClickListener { onClick() }
            val lp = android.widget.LinearLayout.LayoutParams(size, size)
            lp.topMargin = (4 * density).toInt()
            iv.layoutParams = lp
            return iv
        }

        iconCol.addView(mkIcon(R.drawable.ic_refresh, 0xFF1E88E5.toInt()) { onAnalyzeOne(index) })
        if (ord.matched) {
            if (isSaved) {
                iconCol.addView(mkIcon(R.drawable.ic_database, 0xFF43A047.toInt(), enabled = false) {})
                val paidIconRes = if (isPaid) R.drawable.ic_check_circle else R.drawable.ic_radio_off
                val paidTint = if (isPaid)
                    androidx.core.content.ContextCompat.getColor(this, android.R.color.holo_green_dark)
                    else 0xFF90A4AE.toInt()
                iconCol.addView(mkIcon(paidIconRes, paidTint) { onTogglePaid(index) })
            } else {
                iconCol.addView(mkIcon(R.drawable.ic_save, 0xFF1E88E5.toInt()) { onSaveOne(index) })
            }
        } else if (ord.customerName.isNotBlank()) {
            iconCol.addView(mkIcon(R.drawable.ic_scan, 0xFFEF6C00.toInt()) {
                val name = ord.customerName.trim()
                Toast.makeText(this, "Đang tìm \"$name\" trên Zalo...", Toast.LENGTH_SHORT).show()
                triggerSearch(name, "name") { status, msg ->
                    when (status) {
                        "OK" -> {
                            Toast.makeText(this, "Tìm xong ($msg). Đồng bộ & phân tích lại...", Toast.LENGTH_SHORT).show()
                            triggerScanConvs()
                            mainHandler.postDelayed({ onAnalyzeOne(index) }, 600L)
                        }
                        "NO_RESULT" -> showErrorToast("Không tìm thấy \"$name\" trên Zalo")
                        "NO_INPUT" -> showErrorToast("Không tìm thấy ô tìm kiếm Zalo")
                        else -> showErrorToast("Đồng bộ lỗi: $status")
                    }
                }
            })
        }
        frame.addView(iconCol)
        return frame
    }

    private fun triggerExtractSelected() {
        Toast.makeText(this, "Đang trích xuất hội thoại đang chọn...", Toast.LENGTH_SHORT).show()
        webView.evaluateJavascript(
            "window.__autoOrderExtractSelected && window.__autoOrderExtractSelected();",
            null
        )
    }

    private fun refreshCounter() {
        if (ioExecutor.isShutdown) return
        runCatching {
            ioExecutor.execute {
                val today = orderDateFormat.format(java.util.Date())
                val n = runCatching { shopDb.ordersCountToday(today) }.getOrDefault(0)
                mainHandler.post { counter.text = "$n đơn" }
            }
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
        updatePasteQrVisibility()
        refreshCounter()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updatePasteQrVisibility()
    }

    private fun updatePasteQrVisibility() {
        qrPasteBar.visibility = if (PendingQr.dataUrl != null) View.VISIBLE else View.GONE
    }

    private fun pastePendingQr() {
        val data = PendingQr.dataUrl
        if (data.isNullOrEmpty()) {
            Toast.makeText(this, "Không có QR đang chờ", Toast.LENGTH_SHORT).show()
            updatePasteQrVisibility()
            return
        }
        val js = PASTE_QR_JS.replace("__DATA_URL__", data)
        webView.evaluateJavascript(js) { result ->
            val r = (result ?: "").trim('"')
            Log.i(TAG, "PasteQR result=$r")
            when {
                r == "OK" -> {
                    Toast.makeText(this, "Đã dán QR vào ô chat", Toast.LENGTH_SHORT).show()
                    PendingQr.clear()
                    updatePasteQrVisibility()
                }
                r == "NO_INPUT" -> Toast.makeText(
                    this,
                    "Hãy bấm vào ô chat của 1 hội thoại trước, rồi bấm Dán QR",
                    Toast.LENGTH_LONG
                ).show()
                else -> Toast.makeText(this, "Lỗi dán QR: $r", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        val isLastInstance = liveInstance?.get() === this
        if (isLastInstance) {
            liveInstance = null
            OrderExtractor.onActiveChanged = null
            OrderExtractor.dismissActive()
        }
        checkoutDialog?.let { runCatching { it.dismiss() } }
        checkoutDialog = null
        checkPaymentDialog?.let { runCatching { it.dismiss() } }
        checkPaymentDialog = null
        ioExecutor.shutdown()
        runCatching { shopDb.close() }
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
        fun onConversation(animId: String, peerName: String, avatarUrl: String, messagesJson: String) {
            Log.i(TAG, "EXTRACT anim='$animId' peer='$peerName' msgs=${messagesJson.take(200)}")
            mainHandler.post {
                OrderExtractor.extractAndShow(this@ChatWebActivity, animId, peerName, avatarUrl, messagesJson)
            }
        }

        @JavascriptInterface
        fun onConvItem(animId: String, name: String, avatarUrl: String, isGroup: Boolean, timeText: String) {
            if (animId.isBlank()) return
            ioExecutor.execute {
                runCatching {
                    val lastMsgAt = ZaloTimeParser.parse(timeText)
                    shopDb.upsertZaloChat(animId, name, avatarUrl, isGroup, lastMsgAt, timeText)
                }
            }
        }

        @JavascriptInterface
        fun onCheckoutMessages(animId: String, status: String, messagesJson: String) {
            Log.i(TAG, "CHECKOUT anim='$animId' status=$status msgs=${messagesJson.take(200)}")
            mainHandler.post { checkoutCallback?.invoke(animId, status, messagesJson) }
        }

        @JavascriptInterface
        fun onScanDone() {
            mainHandler.post {
                val cb = scanRecentDone
                scanRecentDone = null
                cb?.invoke()
            }
        }

        @JavascriptInterface
        fun onSearchDone(status: String, msg: String) {
            Log.i(TAG, "SEARCH status=$status msg=$msg")
            mainHandler.post {
                val cb = searchDone
                searchDone = null
                cb?.invoke(status, msg)
            }
        }

        @JavascriptInterface
        fun onPaymentCheck(
            status: String, animId: String, peerName: String,
            avatarUrl: String, messagesJson: String
        ) {
            Log.i(TAG, "PAYCHECK status=$status anim='$animId' peer='$peerName' msgs=${messagesJson.take(200)}")
            mainHandler.post {
                val cb = paymentCheckDone
                paymentCheckDone = null
                cb?.invoke(status, animId, peerName, avatarUrl, messagesJson)
            }
        }

        @JavascriptInterface
        fun onSendChatResult(status: String) {
            Log.i(TAG, "SendChat result=$status")
            mainHandler.post {
                val cb = sendChatDone
                sendChatDone = null
                cb?.invoke(status)
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
