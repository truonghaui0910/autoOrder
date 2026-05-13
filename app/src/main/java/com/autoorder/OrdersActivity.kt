package com.autoorder

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import androidx.recyclerview.widget.RecyclerView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class OrdersActivity : AppCompatActivity() {

    private enum class Period { TODAY, YESTERDAY, D7, D30, ALL, CUSTOM }
    private enum class Tab { ORDERS, PRODUCTS, CUSTOMERS }
    private enum class PaidFilter { ALL, UNPAID, PAID }

    private lateinit var db: ShopDb
    private lateinit var ordersAdapter: OrdersAdapter
    private lateinit var soldAdapter: ProductsSoldAdapter
    private lateinit var customersAdapter: CustomersAdapter

    private lateinit var statOrders: TextView
    private lateinit var statRevenue: TextView
    private lateinit var statItems: TextView
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView

    private val chips = mutableListOf<TextView>()
    private val tabs = mutableListOf<TextView>()
    private val paidChips = mutableListOf<TextView>()

    private var period: Period = Period.TODAY
    private var tab: Tab = Tab.ORDERS
    private var paidFilter: PaidFilter = PaidFilter.ALL

    private var customFrom: String? = null
    private var customTo: String? = null

    private lateinit var dateFromView: TextView
    private lateinit var dateToView: TextView
    private lateinit var etSearch: android.widget.EditText
    private lateinit var searchSection: View
    private var searchQuery: String = ""
    private lateinit var filterBody: View
    private lateinit var filterChevron: ImageView
    private lateinit var filterSummary: TextView
    private lateinit var chart: BarChartView
    private lateinit var btnViewList: ImageView
    private lateinit var btnViewChart: ImageView

    private var chartMode: Boolean = false

    private val tz = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    private val priceFormat = NumberFormat.getInstance(Locale("vi", "VN"))
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = tz
    }
    private val displayDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN")).apply {
        timeZone = tz
    }
    private val titleDateFormat = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale("vi", "VN"))
    private val csvFileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = tz
    }
    private val csvRowDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = tz
    }

    private val exportCsvLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) {
                writeCsvToUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_orders)
        db = ShopDb(this)

        statOrders = findViewById(R.id.statOrders)
        statRevenue = findViewById(R.id.statRevenue)
        statItems = findViewById(R.id.statItems)
        emptyView = findViewById(R.id.emptyView)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)

        ordersAdapter = OrdersAdapter(
            emptyList(),
            onClick = { showDetail(it) },
            onTogglePaid = { o ->
                db.setOrderPaid(o.id, !o.paid)
                refresh()
            },
            onCheckPayment = { o -> launchCheckPayment(o) }
        )
        soldAdapter = ProductsSoldAdapter(emptyList())
        customersAdapter = CustomersAdapter(emptyList())

        chips.add(findViewById<TextView>(R.id.chipToday))
        chips.add(findViewById<TextView>(R.id.chipYesterday))
        chips.add(findViewById<TextView>(R.id.chip7))
        chips.add(findViewById<TextView>(R.id.chip30))
        chips.add(findViewById<TextView>(R.id.chipAll))
        chips[0].setOnClickListener { setPeriod(Period.TODAY) }
        chips[1].setOnClickListener { setPeriod(Period.YESTERDAY) }
        chips[2].setOnClickListener { setPeriod(Period.D7) }
        chips[3].setOnClickListener { setPeriod(Period.D30) }
        chips[4].setOnClickListener { setPeriod(Period.ALL) }

        dateFromView = findViewById(R.id.dateFrom)
        dateToView = findViewById(R.id.dateTo)
        dateFromView.setOnClickListener { pickDate(true) }
        dateToView.setOnClickListener { pickDate(false) }

        etSearch = findViewById(R.id.etSearch)
        searchSection = findViewById(R.id.searchSection)
        val clearIcon = androidx.core.content.ContextCompat
            .getDrawable(this, R.drawable.ic_close)?.mutate()?.also {
                it.setTint(0xFF90A4AE.toInt())
                val size = (18 * resources.displayMetrics.density).toInt()
                it.setBounds(0, 0, size, size)
            }
        fun updateClearIcon() {
            val show = etSearch.text.isNotEmpty()
            etSearch.setCompoundDrawables(null, null, if (show) clearIcon else null, null)
            etSearch.compoundDrawablePadding =
                (6 * resources.displayMetrics.density).toInt()
        }
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateClearIcon()
                val newQ = s?.toString()?.trim().orEmpty()
                if (newQ != searchQuery) {
                    searchQuery = newQ
                    AppPrefs.setOrdersSearch(this@OrdersActivity, newQ)
                    refresh()
                }
            }
        })
        etSearch.setOnTouchListener { _, ev ->
            if (ev.action == android.view.MotionEvent.ACTION_UP) {
                val drawable = etSearch.compoundDrawables[2]
                if (drawable != null) {
                    val iconW = drawable.bounds.width()
                    val threshold = etSearch.width - etSearch.paddingEnd - iconW
                    if (ev.x >= threshold) {
                        etSearch.setText("")
                        etSearch.performClick()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
        updateClearIcon()

        paidChips.add(findViewById<TextView>(R.id.chipPaidAll))
        paidChips.add(findViewById<TextView>(R.id.chipUnpaid))
        paidChips.add(findViewById<TextView>(R.id.chipPaid))
        paidChips[0].setOnClickListener { setPaidFilter(PaidFilter.ALL) }
        paidChips[1].setOnClickListener { setPaidFilter(PaidFilter.UNPAID) }
        paidChips[2].setOnClickListener { setPaidFilter(PaidFilter.PAID) }

        filterBody = findViewById(R.id.filterBody)
        filterChevron = findViewById(R.id.filterChevron)
        filterSummary = findViewById(R.id.filterSummary)
        findViewById<View>(R.id.filterHeader).setOnClickListener {
            applyFilterCollapsed(!AppPrefs.isOrdersFilterCollapsed(this), persist = true)
        }
        applyFilterCollapsed(AppPrefs.isOrdersFilterCollapsed(this), persist = false)

        chart = findViewById(R.id.chart)
        btnViewList = findViewById(R.id.btnViewList)
        btnViewChart = findViewById(R.id.btnViewChart)
        btnViewList.setOnClickListener { setChartMode(false) }
        btnViewChart.setOnClickListener { setChartMode(true) }
        setChartMode(AppPrefs.isOrdersChartMode(this), persist = false)

        tabs.add(findViewById<TextView>(R.id.tabOrders))
        tabs.add(findViewById<TextView>(R.id.tabProducts))
        tabs.add(findViewById<TextView>(R.id.tabCustomers))
        tabs[0].setOnClickListener { setTab(Tab.ORDERS) }
        tabs[1].setOnClickListener { setTab(Tab.PRODUCTS) }
        tabs[2].setOnClickListener { setTab(Tab.CUSTOMERS) }

        findViewById<View>(R.id.btnNewOrder).setOnClickListener { showCustomerPicker() }
        findViewById<View>(R.id.btnExportCsv).setOnClickListener { startExportCsv() }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnHome).setOnClickListener {
            startActivity(Intent(this, ChatWebActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        findViewById<View>(R.id.btnCheckout).setOnClickListener {
            startActivity(Intent(this, ChatWebActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("open_checkout", true))
            finish()
        }
        findViewById<View>(R.id.btnDump).setOnClickListener {
            Toast.makeText(this, "Quét DOM phải làm ở màn Chat", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnInbox).setOnClickListener { /* đang ở đây */ }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }

        // Restore filter state đã persist (giữ nguyên khi user quay lại từ
        // Chat sau khi confirm thanh toán).
        val savedRange = AppPrefs.getOrdersCustomRange(this)
        customFrom = savedRange.first
        customTo = savedRange.second
        searchQuery = AppPrefs.getOrdersSearch(this)
        if (searchQuery.isNotEmpty()) etSearch.setText(searchQuery)
        setPaidFilter(runCatching { PaidFilter.valueOf(AppPrefs.getOrdersPaidFilter(this)) }
            .getOrDefault(PaidFilter.ALL))
        setPeriod(runCatching { Period.valueOf(AppPrefs.getOrdersPeriod(this)) }
            .getOrDefault(Period.TODAY))
        setTab(runCatching { Tab.valueOf(AppPrefs.getOrdersTab(this)) }
            .getOrDefault(Tab.ORDERS))
        consumeFocusExtra(intent)
    }

    private var pendingFocusOrderId: Long = -1L

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            consumeFocusExtra(intent)
        }
    }

    private fun consumeFocusExtra(src: Intent?) {
        val id = src?.getLongExtra("focus_order_id", -1L) ?: -1L
        if (id > 0) {
            pendingFocusOrderId = id
            src?.removeExtra("focus_order_id")
        }
    }

    private fun highlightOrderRow(position: Int) {
        list.postDelayed({
            val vh = list.findViewHolderForAdapterPosition(position) ?: return@postDelayed
            val itemView = vh.itemView
            val originalBg = itemView.background
            itemView.setBackgroundColor(0xFFFFF59D.toInt()) // vàng nhạt
            itemView.postDelayed({ itemView.background = originalBg }, 1200L)
        }, 80L)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun setPeriod(p: Period) {
        period = p
        chips.forEachIndexed { i, c -> c.isSelected = i == p.ordinal }
        val (from, to) = rangeFor(p)
        updateDateLabels(from, to)
        AppPrefs.setOrdersPeriod(this, p.name)
        refresh()
    }

    private fun updateDateLabels(from: String?, to: String?) {
        dateFromView.text = from?.let { formatDisplay(it) } ?: "--/--/----"
        dateToView.text = to?.let { formatDisplay(it) } ?: "--/--/----"
    }

    private fun formatDisplay(iso: String): String = try {
        displayDateFormat.format(dateFormat.parse(iso)!!)
    } catch (_: Exception) { iso }

    private fun pickDate(isFrom: Boolean) {
        val (curFrom, curTo) = if (period == Period.CUSTOM) customFrom to customTo else rangeFor(period)
        val initIso = if (isFrom) curFrom else curTo
        val cal = Calendar.getInstance(tz)
        if (initIso != null) {
            try { cal.time = dateFormat.parse(initIso)!! } catch (_: Exception) {}
        }
        DatePickerDialog(this, { _, y, m, d ->
            val c = Calendar.getInstance(tz)
            c.set(y, m, d, 0, 0, 0)
            val picked = dateFormat.format(c.time)
            var newFrom = if (period == Period.CUSTOM) customFrom else curFrom
            var newTo = if (period == Period.CUSTOM) customTo else curTo
            if (isFrom) newFrom = picked else newTo = picked
            if (newFrom != null && newTo != null && newFrom!! > newTo!!) {
                if (isFrom) newTo = newFrom else newFrom = newTo
            }
            customFrom = newFrom
            customTo = newTo
            period = Period.CUSTOM
            chips.forEach { it.isSelected = false }
            updateDateLabels(customFrom, customTo)
            AppPrefs.setOrdersPeriod(this, Period.CUSTOM.name)
            AppPrefs.setOrdersCustomRange(this, customFrom, customTo)
            refresh()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun setPaidFilter(f: PaidFilter) {
        paidFilter = f
        paidChips.forEachIndexed { i, c -> c.isSelected = i == f.ordinal }
        AppPrefs.setOrdersPaidFilter(this, f.name)
        refresh()
    }

    private fun setChartMode(useChart: Boolean, persist: Boolean = true) {
        chartMode = useChart
        val activeColor = 0xFF1E88E5.toInt()
        val inactiveColor = 0xFF546E7A.toInt()
        btnViewList.setColorFilter(if (!useChart) activeColor else inactiveColor)
        btnViewChart.setColorFilter(if (useChart) activeColor else inactiveColor)
        btnViewList.isSelected = !useChart
        btnViewChart.isSelected = useChart
        if (persist) AppPrefs.setOrdersChartMode(this, useChart)
        refresh()
    }

    private fun applyFilterCollapsed(collapsed: Boolean, persist: Boolean) {
        filterBody.visibility = if (collapsed) View.GONE else View.VISIBLE
        filterChevron.rotation = if (collapsed) -90f else 0f
        if (persist) AppPrefs.setOrdersFilterCollapsed(this, collapsed)
        updateFilterSummary()
    }

    private fun updateFilterSummary() {
        val periodLabel = when (period) {
            Period.TODAY -> "Hôm nay"
            Period.YESTERDAY -> "Hôm qua"
            Period.D7 -> "7 ngày"
            Period.D30 -> "30 ngày"
            Period.ALL -> "Tất cả"
            Period.CUSTOM -> {
                val from = customFrom?.let { formatDisplay(it) } ?: "?"
                val to = customTo?.let { formatDisplay(it) } ?: "?"
                "$from → $to"
            }
        }
        val paidLabel = when (paidFilter) {
            PaidFilter.ALL -> "Tất cả TT"
            PaidFilter.UNPAID -> "Chưa TT"
            PaidFilter.PAID -> "Đã TT"
        }
        filterSummary.text = "· $periodLabel · $paidLabel"
    }

    private fun paidArg(): Boolean? = when (paidFilter) {
        PaidFilter.ALL -> null
        PaidFilter.UNPAID -> false
        PaidFilter.PAID -> true
    }

    private fun setTab(t: Tab) {
        tab = t
        tabs.forEachIndexed { i, v -> v.isSelected = i == t.ordinal }
        list.adapter = when (t) {
            Tab.ORDERS -> ordersAdapter
            Tab.PRODUCTS -> soldAdapter
            Tab.CUSTOMERS -> customersAdapter
        }
        if (::searchSection.isInitialized) {
            searchSection.visibility = if (t == Tab.ORDERS) View.VISIBLE else View.GONE
        }
        AppPrefs.setOrdersTab(this, t.name)
        refresh()
    }

    private fun searchArg(): String? =
        if (tab == Tab.ORDERS && searchQuery.isNotBlank()) searchQuery else null

    private fun rangeFor(p: Period): Pair<String?, String?> {
        val now = System.currentTimeMillis()
        val today = dateFormat.format(Date(now))
        return when (p) {
            Period.TODAY -> today to today
            Period.YESTERDAY -> {
                val cal = Calendar.getInstance(tz)
                cal.timeInMillis = now
                cal.add(Calendar.DAY_OF_MONTH, -1)
                val y = dateFormat.format(cal.time)
                y to y
            }
            Period.D7 -> {
                val cal = Calendar.getInstance(tz)
                cal.timeInMillis = now
                cal.add(Calendar.DAY_OF_MONTH, -6)
                dateFormat.format(cal.time) to today
            }
            Period.D30 -> {
                val cal = Calendar.getInstance(tz)
                cal.timeInMillis = now
                cal.add(Calendar.DAY_OF_MONTH, -29)
                dateFormat.format(cal.time) to today
            }
            Period.ALL -> null to null
            Period.CUSTOM -> customFrom to customTo
        }
    }

    private fun refresh() {
        val (from, to) = rangeFor(period)
        if (period == Period.CUSTOM) updateDateLabels(from, to)
        if (::filterSummary.isInitialized) updateFilterSummary()
        val paid = paidArg()
        val search = searchArg()
        val s = db.summary(from, to, paid, search)
        statOrders.text = s.orderCount.toString()
        statRevenue.text = priceFormat.format(s.totalRevenue) + "₫"
        val q = s.totalItems
        statItems.text = if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString()

        if (::chart.isInitialized && chartMode) {
            list.visibility = View.GONE
            chart.visibility = View.VISIBLE
            renderChart(from, to, paid, search)
            return
        }
        if (::chart.isInitialized) {
            chart.visibility = View.GONE
            list.visibility = View.VISIBLE
        }

        when (tab) {
            Tab.ORDERS -> {
                val rows = db.queryOrders(from, to, paid, search, limit = 500)
                val ids = rows.map { it.zaloId }.filter { it.isNotBlank() }.toSet()
                val avatars = if (ids.isEmpty()) emptyMap() else {
                    val msgDb = MessagesDb(this)
                    ids.mapNotNull { id ->
                        msgDb.getZaloChatByZaloId(id)?.takeIf { it.avatarUrl.isNotBlank() }
                            ?.let { id to it.avatarUrl }
                    }.toMap()
                }
                ordersAdapter.submit(rows, avatars)
                emptyView.text = "Chưa có đơn nào trong khoảng đã chọn"
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                if (pendingFocusOrderId > 0) {
                    val pos = rows.indexOfFirst { it.id == pendingFocusOrderId }
                    if (pos >= 0) {
                        list.post {
                            (list.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(pos, 0)
                            highlightOrderRow(pos)
                        }
                    }
                    pendingFocusOrderId = -1L
                }
            }
            Tab.PRODUCTS -> {
                val rows = db.productsSold(from, to, paid, limit = 100)
                soldAdapter.submit(rows)
                emptyView.text = "Chưa bán sản phẩm nào trong khoảng đã chọn"
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
            Tab.CUSTOMERS -> {
                val rows = db.customersStat(from, to, paid, limit = 200)
                customersAdapter.submit(rows)
                emptyView.text = "Chưa có khách hàng nào trong khoảng đã chọn"
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun renderChart(from: String?, to: String?, paid: Boolean?, search: String? = null) {
        emptyView.visibility = View.GONE
        when (tab) {
            Tab.ORDERS -> {
                val data = db.ordersPerDay(from, to, paid, search)
                val barLabelFormat = SimpleDateFormat("dd/MM", Locale("vi", "VN")).apply {
                    timeZone = tz
                }
                val bars = data.takeLast(31).map { (date, count) ->
                    val label = try {
                        barLabelFormat.format(dateFormat.parse(date)!!)
                    } catch (_: Exception) { date }
                    BarChartView.Bar(label, count.toFloat(), count.toString())
                }
                chart.setData("Số đơn theo ngày", bars)
            }
            Tab.PRODUCTS -> {
                val rows = db.productsSold(from, to, paid, limit = 20)
                val bars = rows.map { p ->
                    val q = p.totalQty
                    val qStr = if (q == q.toLong().toDouble()) q.toLong().toString()
                    else String.format("%.1f", q)
                    BarChartView.Bar(p.productName, q.toFloat(), qStr)
                }
                chart.setData("Số lượng đã bán theo sản phẩm", bars)
            }
            Tab.CUSTOMERS -> {
                val rows = db.customersStat(from, to, paid, limit = 15)
                val bars = rows.map { c ->
                    BarChartView.Bar(c.displayName, c.orderCount.toFloat(), c.orderCount.toString())
                }
                chart.setData("Số đơn theo khách hàng", bars)
            }
        }
    }

    private fun showCustomerPicker() {
        OrderExtractor.pickZaloChat(this) { chat ->
            OrderExtractor.showManualOrder(this, chat)
        }
    }

    private fun showDetail(o: OrderRecord) {
        val ctx = this
        val initialItems = db.queryOrderItems(o.id)
        val mutableItems: MutableList<OrderItem> = initialItems.toMutableList()
        val products = db.listProducts(activeOnly = true)
        val productsById = products.associateBy { it.id }

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_order_detail, null, false)
        val dialog = Dialog(ctx, R.style.TransparentDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            val w = (resources.displayMetrics.widthPixels * 0.94).toInt()
            val h = (resources.displayMetrics.heightPixels * 0.88).toInt()
            setLayout(w, h)
        }

        view.findViewById<TextView>(R.id.title).text = "Đơn #${o.id}"
        view.findViewById<TextView>(R.id.subtitle).text = titleDateFormat.format(Date(o.createdAt))

        val etName = view.findViewById<EditText>(R.id.etName)
        val etAddr = view.findViewById<EditText>(R.id.etAddr)
        val etPhone = view.findViewById<EditText>(R.id.etPhone)
        val etOrderNote = view.findViewById<EditText>(R.id.etOrderNote)
        val itemsContainer = view.findViewById<LinearLayout>(R.id.itemsContainer)
        val itemsEmpty = view.findViewById<View>(R.id.itemsEmpty)
        val txtTotalLabel = view.findViewById<TextView>(R.id.txtTotalLabel)
        val totalView = view.findViewById<TextView>(R.id.total)
        val btnAddItem = view.findViewById<Button>(R.id.btnAddItem)
        val copyContent = view.findViewById<TextView>(R.id.copyContent)

        etName.setText(o.senderName)
        val imgAvatar = view.findViewById<ImageView>(R.id.imgAvatar)
        val avatarUrl = if (o.zaloId.isNotBlank())
            runCatching { MessagesDb(ctx).getZaloChatByZaloId(o.zaloId)?.avatarUrl.orEmpty() }
                .getOrDefault("") else ""
        if (avatarUrl.isNotBlank()) {
            imgAvatar.load(avatarUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_avatar_placeholder)
                error(R.drawable.bg_avatar_placeholder)
                transformations(coil.transform.CircleCropTransformation())
            }
        }
        etAddr.setText(o.address)
        etPhone.setText(o.phone)
        etOrderNote.setText(o.note)

        val zaloChat = if (o.zaloId.isNotBlank())
            runCatching { MessagesDb(ctx).getZaloChatByZaloId(o.zaloId) }.getOrNull() else null
        val savedPhones = mutableListOf<String>().apply { zaloChat?.phoneList()?.let { addAll(it) } }
        val savedAddrs = mutableListOf<String>().apply { zaloChat?.addressList()?.let { addAll(it) } }
        val phoneSavedScroll = view.findViewById<View>(R.id.phoneSavedScroll)
        val phoneSavedRow = view.findViewById<LinearLayout>(R.id.phoneSavedRow)
        val addrSavedScroll = view.findViewById<View>(R.id.addrSavedScroll)
        val addrSavedRow = view.findViewById<LinearLayout>(R.id.addrSavedRow)
        val density = resources.displayMetrics.density

        fun makeChip(text: String, onClick: () -> Unit, isSave: Boolean = false): TextView {
            val tv = TextView(ctx)
            tv.text = text
            tv.textSize = 12f
            tv.setTextColor(if (isSave) 0xFF1E88E5.toInt() else 0xFF37474F.toInt())
            val padH = (10 * density).toInt()
            val padV = (5 * density).toInt()
            tv.setPadding(padH, padV, padH, padV)
            tv.setBackgroundResource(if (isSave) R.drawable.bg_chip_customer else R.drawable.bg_chip_normal)
            tv.isClickable = true
            tv.isFocusable = true
            tv.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * density).toInt()
            tv.layoutParams = lp
            return tv
        }

        fun renderSavedRow(
            row: LinearLayout, scroll: View, saved: List<String>, current: String,
            target: EditText, onSaveNew: (String) -> Unit
        ) {
            row.removeAllViews()
            val cur = current.trim()
            saved.forEach { v -> row.addView(makeChip(v, { target.setText(v) })) }
            val isNew = cur.isNotEmpty() && saved.none { it.equals(cur, ignoreCase = true) }
            if (isNew) {
                row.addView(makeChip("+ Lưu \"${cur.take(24)}${if (cur.length > 24) "…" else ""}\"",
                    { onSaveNew(cur) }, isSave = true))
            }
            scroll.visibility = if (row.childCount > 0) View.VISIBLE else View.GONE
        }

        lateinit var refreshPhone: () -> Unit
        lateinit var refreshAddr: () -> Unit
        refreshPhone = {
            renderSavedRow(phoneSavedRow, phoneSavedScroll, savedPhones,
                etPhone.text.toString(), etPhone) { v ->
                if (o.zaloId.isNotBlank() && MessagesDb(ctx).appendPhoneToZaloChat(o.zaloId, v)) {
                    savedPhones.add(v)
                    Toast.makeText(ctx, "Đã lưu SĐT", Toast.LENGTH_SHORT).show()
                }
                refreshPhone()
            }
        }
        refreshAddr = {
            renderSavedRow(addrSavedRow, addrSavedScroll, savedAddrs,
                etAddr.text.toString(), etAddr) { v ->
                if (o.zaloId.isNotBlank() && MessagesDb(ctx).appendAddressToZaloChat(o.zaloId, v)) {
                    savedAddrs.add(v)
                    Toast.makeText(ctx, "Đã lưu địa chỉ", Toast.LENGTH_SHORT).show()
                }
                refreshAddr()
            }
        }

        fun currentRecord(): OrderRecord {
            val total = mutableItems.sumOf { it.lineTotal }
            return o.copy(
                senderName = etName.text.toString().trim(),
                address = etAddr.text.toString().trim(),
                phone = etPhone.text.toString().trim(),
                note = etOrderNote.text.toString().trim(),
                totalAmount = total
            )
        }
        val chkCopyWithPrices = view.findViewById<android.widget.CheckBox>(R.id.chkCopyWithPrices)
        chkCopyWithPrices.isChecked = AppPrefs.isCopyWithPrices(ctx)
        fun refreshCopyContent() {
            copyContent.text = orderToText(currentRecord(), mutableItems, chkCopyWithPrices.isChecked, productsById)
        }
        chkCopyWithPrices.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setCopyWithPrices(ctx, isChecked)
            refreshCopyContent()
        }
        val qrHolder = arrayOfNulls<Bitmap>(1)
        var qrLoadedAmount = -1L
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val qrReload = Runnable {
            val total = mutableItems.sumOf { it.lineTotal }
            if (total <= 0) { qrHolder[0] = null; qrLoadedAmount = -1; return@Runnable }
            if (total == qrLoadedAmount) return@Runnable
            qrLoadedAmount = total
            BankQr.loadAsync(BankQr.vietQrUrl(ctx, total, "MCZUOXQV2HS4LNP 504618370")) { bmp ->
                if (qrLoadedAmount == total && bmp != null) qrHolder[0] = bmp
            }
        }
        fun reloadQrDebounced() {
            mainHandler.removeCallbacks(qrReload)
            mainHandler.postDelayed(qrReload, 350L)
        }
        fun updateGrandTotal() {
            val total = mutableItems.sumOf { it.lineTotal }
            val totalQty = mutableItems.sumOf { it.quantity }
            totalView.text = priceFormat.format(total) + "₫"
            txtTotalLabel.text = "Tổng (${OrderExtractor.formatQty(totalQty)} món)"
            itemsEmpty.visibility = if (mutableItems.isEmpty()) View.VISIBLE else View.GONE
            refreshCopyContent()
            reloadQrDebounced()
        }

        lateinit var renderAll: () -> Unit

        fun bindRow(row: View, idx: Int) {
            val tvProduct = row.findViewById<TextView>(R.id.tvProduct)
            val etQty = row.findViewById<EditText>(R.id.etQty)
            val etNote = row.findViewById<EditText>(R.id.etNote)
            val tvLineTotal = row.findViewById<TextView>(R.id.tvLineTotal)
            val btnMinus = row.findViewById<View>(R.id.btnMinus)
            val btnPlus = row.findViewById<View>(R.id.btnPlus)
            val btnDeleteRow = row.findViewById<View>(R.id.btnDelete)

            val item = mutableItems[idx]
            tvProduct.text = when {
                item.productName.isBlank() -> "(chọn sản phẩm)"
                item.productId == null -> "(off-menu) ${item.productName}"
                else -> item.productName
            }
            etQty.setText(OrderExtractor.formatQty(item.quantity))
            etNote.setText(item.note)
            tvLineTotal.text = OrderExtractor.formatLineTotal(item)

            val qtyWatcher = OrderExtractor.simpleWatch { s ->
                val cur = mutableItems.getOrNull(idx) ?: return@simpleWatch
                val newQty = s.replace(",", ".").toDoubleOrNull()
                if (newQty != null && newQty > 0) {
                    mutableItems[idx] = cur.copy(quantity = newQty)
                    tvLineTotal.text = OrderExtractor.formatLineTotal(mutableItems[idx])
                    updateGrandTotal()
                }
            }
            val noteWatcher = OrderExtractor.simpleWatch { s ->
                val cur = mutableItems.getOrNull(idx) ?: return@simpleWatch
                mutableItems[idx] = cur.copy(note = s)
                refreshCopyContent()
            }
            etQty.addTextChangedListener(qtyWatcher)
            etNote.addTextChangedListener(noteWatcher)

            fun bumpQty(delta: Double) {
                val cur = mutableItems.getOrNull(idx) ?: return
                val newQty = (cur.quantity + delta).coerceAtLeast(1.0)
                mutableItems[idx] = cur.copy(quantity = newQty)
                etQty.removeTextChangedListener(qtyWatcher)
                etQty.setText(OrderExtractor.formatQty(newQty))
                etQty.addTextChangedListener(qtyWatcher)
                tvLineTotal.text = OrderExtractor.formatLineTotal(mutableItems[idx])
                updateGrandTotal()
            }
            btnMinus.setOnClickListener { bumpQty(-1.0) }
            btnPlus.setOnClickListener { bumpQty(+1.0) }

            btnDeleteRow.setOnClickListener {
                if (idx < mutableItems.size) {
                    mutableItems.removeAt(idx)
                    renderAll()
                }
            }

            tvProduct.setOnClickListener {
                OrderExtractor.openProductPicker(ctx, products) { picked ->
                    val cur = mutableItems.getOrNull(idx) ?: return@openProductPicker
                    mutableItems[idx] = cur.copy(
                        productId = picked.id,
                        productName = picked.name,
                        unitPrice = picked.price
                    )
                    tvProduct.text = picked.name
                    tvLineTotal.text = OrderExtractor.formatLineTotal(mutableItems[idx])
                    updateGrandTotal()
                }
            }
        }

        renderAll = {
            itemsContainer.removeAllViews()
            mutableItems.forEachIndexed { idx, _ ->
                val row = LayoutInflater.from(ctx).inflate(R.layout.item_order_row, itemsContainer, false)
                itemsContainer.addView(row)
                bindRow(row, idx)
            }
            updateGrandTotal()
        }
        renderAll()

        btnAddItem.setOnClickListener {
            mutableItems.add(OrderItem(
                productId = null, productName = "", quantity = 1.0,
                unitPrice = 0, note = "", rawText = ""
            ))
            renderAll()
            val newIdx = mutableItems.size - 1
            OrderExtractor.openProductPicker(ctx, products) { picked ->
                val cur = mutableItems.getOrNull(newIdx) ?: return@openProductPicker
                mutableItems[newIdx] = cur.copy(
                    productId = picked.id, productName = picked.name, unitPrice = picked.price
                )
                renderAll()
            }
        }

        etName.addTextChangedListener(OrderExtractor.simpleWatch { refreshCopyContent() })
        etPhone.addTextChangedListener(OrderExtractor.simpleWatch { refreshPhone(); refreshCopyContent() })
        etAddr.addTextChangedListener(OrderExtractor.simpleWatch { refreshAddr(); refreshCopyContent() })

        val btnClearNote = view.findViewById<ImageView>(R.id.btnClearNote)
        fun updateNoteClear() {
            btnClearNote.visibility =
                if (etOrderNote.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        etOrderNote.addTextChangedListener(OrderExtractor.simpleWatch {
            refreshCopyContent(); updateNoteClear()
        })
        updateNoteClear()
        btnClearNote.setOnClickListener { etOrderNote.setText("") }
        refreshPhone()
        refreshAddr()

        val paidIcon = view.findViewById<ImageView>(R.id.paidIcon)
        val paidState = booleanArrayOf(o.paid)
        fun renderPaid() {
            if (paidState[0]) {
                paidIcon.setImageResource(R.drawable.ic_check_circle)
                paidIcon.contentDescription = "Đã thanh toán"
            } else {
                paidIcon.setImageResource(R.drawable.ic_radio_off)
                paidIcon.contentDescription = "Chưa thanh toán"
            }
        }
        renderPaid()
        paidIcon.setOnClickListener {
            paidState[0] = !paidState[0]
            db.setOrderPaid(o.id, paidState[0])
            renderPaid()
            Toast.makeText(this,
                if (paidState[0]) "Đã đánh dấu thanh toán" else "Đánh dấu chưa thanh toán",
                Toast.LENGTH_SHORT).show()
            refresh()
        }

        view.findViewById<View>(R.id.btnDismiss).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val rec = currentRecord()
            copyOrderToClipboard(this, rec, mutableItems, chkCopyWithPrices.isChecked, productsById)
            qrHolder[0]?.let { PendingQr.dataUrl = BankQr.bitmapToDataUrl(it) }
            Toast.makeText(this, "Đã copy đơn", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnSendChat)?.setOnClickListener { btn ->
            val rec = currentRecord()
            val orderText = orderToText(rec, mutableItems, chkCopyWithPrices.isChecked, productsById)
            copyOrderToClipboard(this, rec, mutableItems, chkCopyWithPrices.isChecked, productsById)
            qrHolder[0]?.let { PendingQr.dataUrl = BankQr.bitmapToDataUrl(it) }
            btn.isEnabled = false
            val originalText = (btn as? Button)?.text?.toString() ?: "Gửi khách"
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val countdown = object : Runnable {
                var remain = 5
                override fun run() {
                    if (remain > 0) {
                        (btn as? Button)?.text = "Đợi ${remain}s"
                        remain--
                        handler.postDelayed(this, 1000L)
                    } else {
                        (btn as? Button)?.text = originalText
                        btn.isEnabled = true
                    }
                }
            }
            handler.post(countdown)
            val started = ChatWebActivity.requestSendChat(orderText) { status ->
                when {
                    status == "OK" -> Toast.makeText(this, "Đã gửi cho khách", Toast.LENGTH_SHORT).show()
                    status == "NO_INPUT" -> Toast.makeText(this,
                        "Không tìm thấy ô chat. Hãy mở 1 hội thoại trước.", Toast.LENGTH_LONG).show()
                    status == "NO_BTN" -> Toast.makeText(this,
                        "Không tìm thấy nút gửi của Zalo", Toast.LENGTH_LONG).show()
                    else -> Toast.makeText(this, "Gửi lỗi: $status", Toast.LENGTH_LONG).show()
                }
            }
            if (!started) {
                handler.removeCallbacks(countdown)
                (btn as? Button)?.text = originalText
                btn.isEnabled = true
                Toast.makeText(this, "Chat Web chưa mở, không thể gửi", Toast.LENGTH_LONG).show()
            }
        }

        view.findViewById<Button>(R.id.btnSendGroup)?.setOnClickListener { btn ->
            val groupChat = runCatching { MessagesDb(this).getFirstOrderGroupChat() }.getOrNull()
            if (groupChat == null || groupChat.name.isBlank()) {
                Toast.makeText(this, "Chưa có nhóm nào loại 'order' đang active", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val rec = currentRecord()
            val orderText = orderToText(rec, mutableItems, chkCopyWithPrices.isChecked, productsById)
            copyOrderToClipboard(this, rec, mutableItems, chkCopyWithPrices.isChecked, productsById)
            qrHolder[0]?.let { PendingQr.dataUrl = BankQr.bitmapToDataUrl(it) }
            btn.isEnabled = false
            val originalText = (btn as? Button)?.text?.toString() ?: "Gửi nhóm"
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val countdown = object : Runnable {
                var remain = 5
                override fun run() {
                    if (remain > 0) {
                        (btn as? Button)?.text = "Đợi ${remain}s"
                        remain--
                        handler.postDelayed(this, 1000L)
                    } else {
                        (btn as? Button)?.text = originalText
                        btn.isEnabled = true
                    }
                }
            }
            handler.post(countdown)

            val searchStarted = ChatWebActivity.requestSearch(groupChat.name, "name") { sStatus, _ ->
                if (sStatus != "OK") {
                    handler.removeCallbacks(countdown)
                    (btn as? Button)?.text = originalText
                    btn.isEnabled = true
                    val msg = when (sStatus) {
                        "NO_INPUT" -> "Không tìm thấy ô tìm kiếm Zalo"
                        "NO_RESULT" -> "Không tìm thấy nhóm '${groupChat.name}' trên Zalo"
                        else -> "Tìm nhóm lỗi: $sStatus"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    return@requestSearch
                }
                handler.postDelayed({
                    val sendStarted = ChatWebActivity.requestSendChat(orderText) { status ->
                        when {
                            status == "OK" -> Toast.makeText(this,
                                "Đã gửi vào nhóm ${groupChat.name}", Toast.LENGTH_SHORT).show()
                            status == "NO_INPUT" -> Toast.makeText(this,
                                "Không tìm thấy ô chat nhóm", Toast.LENGTH_LONG).show()
                            status == "NO_BTN" -> Toast.makeText(this,
                                "Không tìm thấy nút gửi của Zalo", Toast.LENGTH_LONG).show()
                            else -> Toast.makeText(this,
                                "Gửi nhóm lỗi: $status", Toast.LENGTH_LONG).show()
                        }
                    }
                    if (!sendStarted) {
                        handler.removeCallbacks(countdown)
                        (btn as? Button)?.text = originalText
                        btn.isEnabled = true
                        Toast.makeText(this, "Chat Web chưa mở, không thể gửi", Toast.LENGTH_LONG).show()
                    }
                }, 600L)
            }
            if (!searchStarted) {
                handler.removeCallbacks(countdown)
                (btn as? Button)?.text = originalText
                btn.isEnabled = true
                Toast.makeText(this, "Chat Web chưa mở, không thể gửi", Toast.LENGTH_LONG).show()
            }
        }
        view.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val validItems = mutableItems.filter { it.productName.isNotBlank() }
            if (validItems.isEmpty()) {
                Toast.makeText(this, "Đơn không có món nào để lưu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val total = validItems.sumOf { it.lineTotal }
            val itemsText = OrderExtractor.buildItemsText(validItems, withPrices = false)
            val updated = o.copy(
                senderName = etName.text.toString().trim(),
                phone = etPhone.text.toString().trim(),
                address = etAddr.text.toString().trim(),
                note = etOrderNote.text.toString().trim(),
                itemsText = itemsText,
                totalAmount = total,
                paid = paidState[0],
                orderCode = OrderRecord.makeCode(o.zaloId, o.orderDate, total)
            )
            runCatching { db.updateOrder(o.id, updated, validItems) }
                .onSuccess {
                    copyOrderToClipboard(this, updated, validItems, chkCopyWithPrices.isChecked, productsById)
                    qrHolder[0]?.let { PendingQr.dataUrl = BankQr.bitmapToDataUrl(it) }
                    Toast.makeText(this, "Đã lưu đơn #${o.id} & copy", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    refresh()
                }
                .onFailure {
                    AlertDialog.Builder(this)
                        .setTitle("Lỗi lưu đơn")
                        .setMessage(it.message ?: "Unknown")
                        .setPositiveButton("OK", null)
                        .show()
                }
        }
        view.findViewById<Button>(R.id.btnDeleteOrder).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Xoá đơn")
                .setMessage("Xoá đơn #${o.id}? Hành động không hoàn tác được.")
                .setNegativeButton("Huỷ", null)
                .setPositiveButton("Xoá") { _, _ ->
                    db.deleteOrder(o.id)
                    dialog.dismiss()
                    refresh()
                    Toast.makeText(this, "Đã xoá", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        dialog.show()
    }

    private fun launchCheckPayment(o: OrderRecord) {
        val phone = o.phone.trim()
        if (phone.isBlank()) {
            Toast.makeText(this, "Đơn không có SĐT", Toast.LENGTH_SHORT).show()
            return
        }
        val avatarUrl = if (o.zaloId.isNotBlank()) {
            runCatching {
                MessagesDb(this).getZaloChatByZaloId(o.zaloId)?.avatarUrl.orEmpty()
            }.getOrDefault("")
        } else ""
        startActivity(Intent(this, ChatWebActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("check_payment_order_id", o.id)
            .putExtra("check_payment_phone", phone)
            .putExtra("check_payment_total", o.totalAmount)
            .putExtra("check_payment_sender", o.senderName)
            .putExtra("check_payment_avatar", avatarUrl))
    }

    private fun startExportCsv() {
        val suffix = csvFileDateFormat.format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "orders_$suffix.csv")
        }
        try {
            exportCsvLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Không mở được trình chọn file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeCsvToUri(uri: Uri) {
        val (from, to) = rangeFor(period)
        val paid = paidArg()
        val search = searchArg()
        val orders = db.queryOrders(from, to, paid, search, limit = Int.MAX_VALUE)
        if (orders.isEmpty()) {
            Toast.makeText(this, "Không có đơn nào để xuất", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { os ->
                os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                os.writer(Charsets.UTF_8).use { w ->
                    val header = listOf(
                        "id", "order_code", "created_at", "order_date",
                        "sender_name", "conv_name", "phone", "address",
                        "items", "total_amount", "paid", "note", "zalo_id"
                    )
                    w.append(header.joinToString(",") { csvEscape(it) }).append("\r\n")
                    for (o in orders) {
                        val items = db.queryOrderItems(o.id)
                        val itemsStr = items.joinToString("; ") { it ->
                            val q = if (it.quantity == it.quantity.toLong().toDouble())
                                it.quantity.toLong().toString()
                            else it.quantity.toString()
                            val base = "$q x ${it.productName}"
                            if (it.note.isNotBlank()) "$base (${it.note})" else base
                        }
                        val code = o.orderCode.ifBlank {
                            OrderRecord.makeCode(o.zaloId, o.orderDate, o.totalAmount)
                        }
                        val row = listOf(
                            o.id.toString(),
                            code,
                            csvRowDateFormat.format(Date(o.createdAt)),
                            o.orderDate,
                            o.senderName,
                            o.convName,
                            o.phone,
                            o.address,
                            itemsStr,
                            o.totalAmount.toString(),
                            if (o.paid) "1" else "0",
                            o.note,
                            o.zaloId
                        )
                        w.append(row.joinToString(",") { csvEscape(it) }).append("\r\n")
                    }
                }
            } ?: run {
                Toast.makeText(this, "Không mở được file để ghi", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "Đã xuất ${orders.size} đơn", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi xuất CSV: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun csvEscape(s: String): String {
        val needsQuote = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')
        val escaped = s.replace("\"", "\"\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }

    companion object {
        fun orderToText(
            o: OrderRecord,
            items: List<OrderItem>,
            withPrices: Boolean = true,
            productsById: Map<Long, Product>? = null
        ): String {
            val nf = NumberFormat.getInstance(Locale("vi", "VN"))
            return buildString {
                append("Tên: ").append(o.senderName).append('\n')
                if (o.note.isNotBlank()) append("Ghi chú: ").append(o.note.trim()).append('\n')
                append('\n')
                items.forEach { it ->
                    val q = if (it.quantity == it.quantity.toLong().toDouble())
                        it.quantity.toLong().toString()
                    else it.quantity.toString()
                    append(q).append(" x ").append(it.productName)
                    if (it.note.isNotBlank()) append(" (").append(it.note).append(")")
                    if (withPrices && it.unitPrice > 0) append(" — ").append(nf.format(it.lineTotal)).append("₫")
                    append('\n')
                }
                if (o.totalAmount > 0) {
                    append("Tổng: ").append(nf.format(o.totalAmount)).append("₫")
                    if (productsById != null) {
                        val byCat = LinkedHashMap<String, Double>()
                        items.forEach { oi ->
                            val cat = oi.productId?.let { productsById[it]?.category }
                                ?.takeIf { c -> c.isNotBlank() } ?: "Khác"
                            byCat[cat] = (byCat[cat] ?: 0.0) + oi.quantity
                        }
                        if (byCat.isNotEmpty()) {
                            val parts = byCat.entries.joinToString(", ") { e ->
                                val v = e.value
                                val qStr = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
                                "${e.key}: $qStr"
                            }
                            append(" (").append(parts).append(")")
                        }
                    }
                    append('\n')
                }
                append('\n')
                append("SĐT: ").append(o.phone).append('\n')
                append("Địa chỉ: ").append(o.address)
            }
        }

        fun copyOrderToClipboard(
            ctx: Context,
            o: OrderRecord,
            items: List<OrderItem>,
            withPrices: Boolean = true,
            productsById: Map<Long, Product>? = null
        ) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Đơn hàng", orderToText(o, items, withPrices, productsById)))
        }
    }
}
