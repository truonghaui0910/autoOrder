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
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class ChatWebActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AutoOrder"
        private const val URL = "https://chat.zalo.me/"

        @Volatile
        private var liveInstance: java.lang.ref.WeakReference<ChatWebActivity>? = null

        fun requestScanConvs(): Boolean {
            val act = liveInstance?.get() ?: return false
            act.runOnUiThread { act.triggerScanConvs() }
            return true
        }

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

        liveInstance = java.lang.ref.WeakReference(this)
        webView = findViewById(R.id.webView)
        counter = findViewById(R.id.counter)
        qrPasteBar = findViewById(R.id.qrPasteBar)
        btnPasteQr = findViewById(R.id.btnPasteQr)
        db = MessagesDb(this)
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
            if (d != null) {
                if (!d.isShowing) d.show()
            } else {
                openCheckoutDialog()
            }
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

        OrderExtractor.onOrderSaved = { mainHandler.post { refreshCounter() } }
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

    private var checkoutCallback: ((String, String, String) -> Unit)? = null
    private var checkoutDialog: android.app.Dialog? = null

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
        val msgDb = MessagesDb(this)
        val chats = msgDb.listZaloChats().filter {
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
        val btnConfirm = view.findViewById<android.widget.Button>(R.id.btnConfirm)
        val btnDismiss = view.findViewById<View>(R.id.btnDismiss)
        val btnMinimize = view.findViewById<View>(R.id.btnMinimize)
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
        val tvMessagesLabel = view.findViewById<TextView>(R.id.tvMessagesLabel)
        val messagesContainer = view.findViewById<android.widget.LinearLayout>(R.id.messagesContainer)

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

        btnDismiss.setOnClickListener {
            checkoutCallback = null
            checkoutDialog = null
            dialog.dismiss()
        }
        btnMinimize.setOnClickListener { dialog.hide() }
        checkoutDialog = dialog
        setBottomBarItemActive(R.id.btnCheckout, true)

        btnConfirm.setOnClickListener {
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
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "Đang đọc tin nhắn của ${chat.name.ifBlank { zaloId }}..."
            tvMessagesLabel.visibility = View.GONE
            messagesContainer.removeAllViews()
            btnConfirm.isEnabled = false

            checkoutCallback = cb@{ animId, status, json ->
                btnConfirm.isEnabled = true
                if (animId != zaloId) return@cb
                when (status) {
                    "OK" -> renderCheckoutMessages(messagesContainer, tvStatus, tvMessagesLabel, json, pickedDate, chat.name)
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

        dialog.setOnDismissListener {
            checkoutCallback = null
            if (checkoutDialog === dialog) {
                checkoutDialog = null
                setBottomBarItemActive(R.id.btnCheckout, false)
            }
        }
        dialog.show()
    }

    private fun renderCheckoutMessages(
        container: android.widget.LinearLayout,
        status: TextView,
        label: TextView,
        json: String,
        pickedDate: String,
        chatName: String
    ) {
        val arr = runCatching { org.json.JSONArray(json) }.getOrNull()
        if (arr == null || arr.length() == 0) {
            label.visibility = View.GONE
            status.text = "Không có tin nhắn nào trong hội thoại."
            return
        }
        label.visibility = View.VISIBLE
        label.text = "Tin nhắn  /  Đơn hàng AI"

        val texts = ArrayList<String>(arr.length())
        val timesMs = ArrayList<Long>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            texts.add(o.optString("text"))
            timesMs.add(o.optString("time").toLongOrNull() ?: 0L)
        }

        renderPairedRows(container, texts, timesMs, emptyList(), 0)
        status.text = "${chatName} · $pickedDate · ${texts.size} tin · đang phân tích AI..."

        val aiInput = org.json.JSONArray().apply {
            texts.forEachIndexed { i, t ->
                put(org.json.JSONObject().put("index", i).put("text", t))
            }
        }.toString()

        OrderExtractor.analyzeBatchOrders(
            this,
            aiInput,
            onProgress = { partial, done, totalChunks ->
                val matched = partial.count { it.matched }
                val analyzed = minOf(done * 10, texts.size)
                status.text = "${chatName} · $pickedDate · ${texts.size} tin · AI $done/$totalChunks · ${partial.size} đơn (${matched} khớp)"
                renderPairedRows(container, texts, timesMs, partial, analyzed)
            }
        ) { result ->
            result.onSuccess { orders ->
                val matched = orders.count { it.matched }
                status.text = "${chatName} · $pickedDate · ${texts.size} tin · ${orders.size} đơn (${matched} khớp Zalo)"
                renderPairedRows(container, texts, timesMs, orders, texts.size)
            }.onFailure { e ->
                Log.e(TAG, "analyzeBatchOrders fail", e)
                status.text = "Lỗi AI: ${e.message ?: "Unknown"}"
                renderPairedRows(container, texts, timesMs, emptyList(), texts.size)
            }
        }
    }

    private fun renderPairedRows(
        container: android.widget.LinearLayout,
        texts: List<String>,
        timesMs: List<Long>,
        orders: List<OrderExtractor.BatchOrder>,
        analyzedCount: Int
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

        val ordersByIndex = orders.groupBy { it.messageIndex }

        for (i in texts.indices) {
            val row = android.widget.LinearLayout(this)
            row.orientation = android.widget.LinearLayout.HORIZONTAL
            row.weightSum = 2f
            val rowLp = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            rowLp.topMargin = marginV
            rowLp.bottomMargin = marginV
            row.layoutParams = rowLp

            // LEFT: message bubble
            val leftCol = android.widget.LinearLayout(this)
            leftCol.orientation = android.widget.LinearLayout.VERTICAL
            val leftLp = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
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

            // RIGHT: order card or placeholder
            val rightCol = android.widget.LinearLayout(this)
            rightCol.orientation = android.widget.LinearLayout.VERTICAL
            val rightLp = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            rightLp.marginStart = gap / 2
            rightCol.layoutParams = rightLp

            val ordersForMsg = ordersByIndex[i].orEmpty()
            val pending = i >= analyzedCount
            if (pending) {
                rightCol.addView(buildLoadingCard(padH, padV, density))
            } else if (ordersForMsg.isEmpty()) {
                val placeholder = TextView(this)
                placeholder.text = "(không phải đơn)"
                placeholder.setPadding(padH, padV, padH, padV)
                placeholder.textSize = 11f
                placeholder.setTextColor(0xFF90A4AE.toInt())
                placeholder.background = androidx.core.content.ContextCompat.getDrawable(
                    this, R.drawable.bg_input_field
                )
                rightCol.addView(placeholder, android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            } else {
                ordersForMsg.forEach { ord ->
                    rightCol.addView(buildOrderCard(ord, padH, padV, density))
                }
            }

            row.addView(leftCol)
            row.addView(rightCol)
            container.addView(row)
        }
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

    private fun buildOrderCard(
        ord: OrderExtractor.BatchOrder,
        padH: Int, padV: Int, density: Float
    ): View {
        val card = android.widget.LinearLayout(this)
        card.orientation = android.widget.LinearLayout.VERTICAL
        card.setPadding(padH, padV, padH, padV)
        card.background = androidx.core.content.ContextCompat.getDrawable(
            this, R.drawable.bg_input_field
        )
        val cardLp = android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        cardLp.bottomMargin = (4 * density).toInt()
        card.layoutParams = cardLp

        val priceFmt = java.text.NumberFormat.getInstance(java.util.Locale("vi", "VN"))

        val title = TextView(this)
        val nameLabel = ord.customerName.ifBlank { "(không tên)" }
        title.text = if (ord.matched) "✓ $nameLabel" else "⚠ $nameLabel"
        title.setTextColor(if (ord.matched) 0xFF1E88E5.toInt() else 0xFFEF6C00.toInt())
        title.textSize = 12f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        card.addView(title)

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
        if (!ord.matched && ord.customerName.isNotBlank()) {
            val tvWarn = TextView(this)
            tvWarn.text = "⚠ Không tìm thấy zaloId cho \"${ord.customerName}\""
            tvWarn.textSize = 10f
            tvWarn.setTextColor(0xFFEF6C00.toInt())
            card.addView(tvWarn)
        }
        return card
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
        if (liveInstance?.get() === this) liveInstance = null
        OrderExtractor.onActiveChanged = null
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
                    db.upsertZaloChat(animId, name, avatarUrl, isGroup, lastMsgAt, timeText)
                }
            }
        }

        @JavascriptInterface
        fun onCheckoutMessages(animId: String, status: String, messagesJson: String) {
            Log.i(TAG, "CHECKOUT anim='$animId' status=$status msgs=${messagesJson.take(200)}")
            mainHandler.post { checkoutCallback?.invoke(animId, status, messagesJson) }
        }

        @JavascriptInterface
        fun onDump(tag: String, cssClass: String, text: String, dataAttrs: String) {
            val cls = if (cssClass.isBlank()) "" else " cls='${cssClass.take(120)}'"
            val data = if (dataAttrs.isBlank()) "" else " data='${dataAttrs.take(120)}'"
            Log.d(TAG, "DUMP <$tag>$cls$data :: $text")
        }
    }
}
