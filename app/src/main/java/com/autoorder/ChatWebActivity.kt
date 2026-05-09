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
import coil.load
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

        fun requestScanConvsRecent(hours: Double, onDone: () -> Unit): Boolean {
            val act = liveInstance?.get() ?: return false
            act.runOnUiThread { act.triggerScanConvsRecent(hours, onDone) }
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

    private var scanRecentDone: (() -> Unit)? = null

    fun triggerScanConvsRecent(hours: Double, onDone: () -> Unit) {
        scanRecentDone = onDone
        webView.evaluateJavascript(
            "window.__autoOrderScanRecent && window.__autoOrderScanRecent($hours);", null
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
        val btnRead = view.findViewById<android.widget.Button>(R.id.btnReadMessages)
        val btnAnalyze = view.findViewById<android.widget.Button>(R.id.btnAnalyze)
        val btnDismiss = view.findViewById<View>(R.id.btnDismiss)
        val btnMinimize = view.findViewById<View>(R.id.btnMinimize)
        btnAnalyze.isEnabled = false
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

        var texts: List<String> = emptyList()
        var timesMs: List<Long> = emptyList()
        val ordersByIndex = LinkedHashMap<Int, OrderExtractor.BatchOrder>()
        val loadingIndices = mutableSetOf<Int>()
        val analyzedIndices = mutableSetOf<Int>()
        var currentChatName = ""
        var currentDate = ""

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

        rerender = {
            renderPairedRows(
                messagesContainer, texts, timesMs,
                ordersByIndex, loadingIndices, analyzedIndices, analyzeOne
            )
            btnAnalyze.isEnabled = texts.isNotEmpty() &&
                (texts.indices).any { it !in analyzedIndices && it !in loadingIndices }
            updateStatus()
        }

        analyzeOne = { idx ->
            if (idx in 0 until texts.size && idx !in loadingIndices) {
                loadingIndices.add(idx)
                ordersByIndex.remove(idx)
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
                }
                rerender()
                val input = org.json.JSONArray().apply {
                    indices.forEach { i ->
                        put(org.json.JSONObject().put("index", i).put("text", texts[i]))
                    }
                }.toString()
                btnAnalyze.isEnabled = false
                OrderExtractor.analyzeBatchOrders(
                    this, input,
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
                    }
                ) { result ->
                    indices.forEach { loadingIndices.remove(it) }
                    result.onSuccess { orders ->
                        indices.forEach { analyzedIndices.add(it) }
                        orders.forEach { ord ->
                            if (ord.messageIndex in indices) ordersByIndex[ord.messageIndex] = ord
                        }
                    }.onFailure { e ->
                        Log.e(TAG, "analyzeBatchOrders fail", e)
                        Toast.makeText(this, "Lỗi AI: ${e.message ?: "?"}", Toast.LENGTH_SHORT).show()
                    }
                    rerender()
                }
            }
        }

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
            texts = emptyList(); timesMs = emptyList()
            ordersByIndex.clear(); loadingIndices.clear(); analyzedIndices.clear()
            btnRead.isEnabled = false
            btnAnalyze.isEnabled = false

            checkoutCallback = cb@{ animId, status, json ->
                btnRead.isEnabled = true
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
                        for (k in 0 until arr.length()) {
                            val o = arr.optJSONObject(k) ?: continue
                            newTexts.add(o.optString("text"))
                            newTimesMs.add(o.optString("time").toLongOrNull() ?: 0L)
                        }
                        texts = newTexts
                        timesMs = newTimesMs
                        tvMessagesLabel.visibility = View.VISIBLE
                        tvMessagesLabel.text = "Tin nhắn  /  Đơn hàng AI"
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
            val pending = (texts.indices).filter {
                it !in analyzedIndices && it !in loadingIndices
            }
            analyzeMany(pending)
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
        ordersByIndex: Map<Int, OrderExtractor.BatchOrder>,
        loadingIndices: Set<Int>,
        analyzedIndices: Set<Int>,
        onAnalyzeOne: (Int) -> Unit
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

            val isLoading = i in loadingIndices
            val isAnalyzed = i in analyzedIndices
            val order = ordersByIndex[i]
            when {
                isLoading -> rightCol.addView(buildLoadingCard(padH, padV, density))
                isAnalyzed && order != null -> {
                    rightCol.addView(buildOrderCard(order, padH, padV, density))
                    rightCol.addView(buildAnalyzeButton(i, "↻ Phân tích lại", density, onAnalyzeOne))
                }
                isAnalyzed -> {
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
                    rightCol.addView(buildAnalyzeButton(i, "↻ Phân tích lại", density, onAnalyzeOne))
                }
                else -> rightCol.addView(buildAnalyzeButton(i, "Phân tích", density, onAnalyzeOne))
            }

            row.addView(leftCol)
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

        val nameLabel = ord.customerName.ifBlank { "(không tên)" }
        if (ord.matched) {
            val header = android.widget.LinearLayout(this)
            header.orientation = android.widget.LinearLayout.HORIZONTAL
            header.gravity = android.view.Gravity.CENTER_VERTICAL

            val avatarSize = (24 * density).toInt()
            val avatar = android.widget.ImageView(this)
            val avLp = android.widget.LinearLayout.LayoutParams(avatarSize, avatarSize)
            avLp.marginEnd = (6 * density).toInt()
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

            val title = TextView(this)
            title.text = "✓ $nameLabel"
            title.setTextColor(0xFF1E88E5.toInt())
            title.textSize = 12f
            title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
            title.maxLines = 1
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            nameCol.addView(title)

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
            val title = TextView(this)
            title.text = "⚠ $nameLabel"
            title.setTextColor(0xFFEF6C00.toInt())
            title.textSize = 12f
            title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
            card.addView(title)
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
        fun onScanDone() {
            mainHandler.post {
                val cb = scanRecentDone
                scanRecentDone = null
                cb?.invoke()
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
