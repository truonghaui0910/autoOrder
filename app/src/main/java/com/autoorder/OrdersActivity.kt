package com.autoorder

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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
            }
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

        paidChips.add(findViewById<TextView>(R.id.chipPaidAll))
        paidChips.add(findViewById<TextView>(R.id.chipUnpaid))
        paidChips.add(findViewById<TextView>(R.id.chipPaid))
        paidChips[0].setOnClickListener { setPaidFilter(PaidFilter.ALL) }
        paidChips[1].setOnClickListener { setPaidFilter(PaidFilter.UNPAID) }
        paidChips[2].setOnClickListener { setPaidFilter(PaidFilter.PAID) }
        setPaidFilter(PaidFilter.ALL)

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

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnHome).setOnClickListener {
            startActivity(Intent(this, ChatWebActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        findViewById<View>(R.id.btnReload).setOnClickListener { refresh() }
        findViewById<View>(R.id.btnDump).setOnClickListener {
            Toast.makeText(this, "Quét DOM phải làm ở màn Chat", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnInbox).setOnClickListener { /* đang ở đây */ }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }

        setPeriod(Period.TODAY)
        setTab(Tab.ORDERS)
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
            refresh()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun setPaidFilter(f: PaidFilter) {
        paidFilter = f
        paidChips.forEachIndexed { i, c -> c.isSelected = i == f.ordinal }
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
        refresh()
    }

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
        val s = db.summary(from, to, paid)
        statOrders.text = s.orderCount.toString()
        statRevenue.text = priceFormat.format(s.totalRevenue) + "₫"
        val q = s.totalItems
        statItems.text = if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString()

        if (::chart.isInitialized && chartMode) {
            list.visibility = View.GONE
            chart.visibility = View.VISIBLE
            renderChart(from, to, paid)
            return
        }
        if (::chart.isInitialized) {
            chart.visibility = View.GONE
            list.visibility = View.VISIBLE
        }

        when (tab) {
            Tab.ORDERS -> {
                val rows = db.queryOrders(from, to, paid, limit = 500)
                ordersAdapter.submit(rows)
                emptyView.text = "Chưa có đơn nào trong khoảng đã chọn"
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
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

    private fun renderChart(from: String?, to: String?, paid: Boolean?) {
        emptyView.visibility = View.GONE
        when (tab) {
            Tab.ORDERS -> {
                val data = db.ordersPerDay(from, to, paid)
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

    private fun showDetail(o: OrderRecord) {
        val items = db.queryOrderItems(o.id)
        val dialog = Dialog(this, R.style.TransparentDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_order_detail)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            val w = (resources.displayMetrics.widthPixels * 0.94).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        dialog.findViewById<TextView>(R.id.title).text =
            "Đơn #${o.id} — ${o.senderName.ifBlank { o.convName }}"
        dialog.findViewById<TextView>(R.id.subtitle).text = titleDateFormat.format(Date(o.createdAt))
        val contact = buildString {
            if (o.phone.isNotBlank()) append("SĐT: ").append(o.phone).append('\n')
            if (o.address.isNotBlank()) append("Địa chỉ: ").append(o.address)
        }.trim()
        dialog.findViewById<TextView>(R.id.contact).text = contact.ifBlank { "(không có thông tin liên hệ)" }

        val sb = StringBuilder()
        items.forEach { it ->
            val qty = if (it.quantity == it.quantity.toLong().toDouble())
                it.quantity.toLong().toString()
            else
                it.quantity.toString()
            sb.append(qty).append(" x ").append(it.productName)
            if (it.note.isNotBlank()) sb.append(" (").append(it.note).append(")")
            if (it.unitPrice <= 0) sb.append("  (chưa map sản phẩm)")
            sb.append('\n')
        }
        dialog.findViewById<TextView>(R.id.itemsBlock).text = sb.toString().trimEnd()

        val paidIcon = dialog.findViewById<ImageView>(R.id.paidIcon)
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
        dialog.findViewById<TextView>(R.id.total).text = priceFormat.format(o.totalAmount) + "₫"

        val bankInfo = dialog.findViewById<TextView>(R.id.bankInfo)
        val qrImage = dialog.findViewById<ImageView>(R.id.qrImage)
        val qrStatus = dialog.findViewById<TextView>(R.id.qrStatus)
        bankInfo.text = BankQr.infoText(this)
        val qrHolder = arrayOfNulls<Bitmap>(1)
        if (o.totalAmount > 0) {
            qrStatus.text = "Đang tạo QR..."
            BankQr.loadAsync(BankQr.vietQrUrl(this, o.totalAmount.toLong(), "Don ${o.id}")) { bmp ->
                if (bmp != null) {
                    qrImage.setImageBitmap(bmp)
                    qrStatus.visibility = View.GONE
                    qrHolder[0] = bmp
                } else {
                    qrStatus.text = "Không tải được QR"
                }
            }
        } else {
            qrStatus.text = "Đơn không có tổng tiền"
        }

        dialog.findViewById<View>(R.id.btnDismiss).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<Button>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<Button>(R.id.btnCopy).setOnClickListener {
            copyOrderToClipboard(this, o, items)
            qrHolder[0]?.let { PendingQr.dataUrl = BankQr.bitmapToDataUrl(it) }
            Toast.makeText(this, "Đã copy đơn", Toast.LENGTH_SHORT).show()
        }
        dialog.findViewById<Button>(R.id.btnDelete).setOnClickListener {
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

    companion object {
        fun orderToText(o: OrderRecord, items: List<OrderItem>): String {
            val nf = NumberFormat.getInstance(Locale("vi", "VN"))
            return buildString {
                append("Tên: ").append(o.senderName).append("\n\n")
                items.forEach { it ->
                    val q = if (it.quantity == it.quantity.toLong().toDouble())
                        it.quantity.toLong().toString()
                    else it.quantity.toString()
                    append(q).append(" x ").append(it.productName)
                    if (it.note.isNotBlank()) append(" (").append(it.note).append(")")
                    if (it.unitPrice > 0) append(" — ").append(nf.format(it.lineTotal)).append("₫")
                    append('\n')
                }
                if (o.totalAmount > 0) append("Tổng: ").append(nf.format(o.totalAmount)).append("₫\n")
                append('\n')
                append("SĐT: ").append(o.phone).append('\n')
                append("Địa chỉ: ").append(o.address)
            }
        }

        fun copyOrderToClipboard(ctx: Context, o: OrderRecord, items: List<OrderItem>) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Đơn hàng", orderToText(o, items)))
        }
    }
}
