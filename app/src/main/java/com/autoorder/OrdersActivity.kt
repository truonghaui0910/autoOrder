package com.autoorder

import android.app.AlertDialog
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

    private enum class Period { TODAY, D7, D30, ALL }
    private enum class Tab { ORDERS, PRODUCTS }

    private lateinit var db: ShopDb
    private lateinit var ordersAdapter: OrdersAdapter
    private lateinit var soldAdapter: ProductsSoldAdapter

    private lateinit var statOrders: TextView
    private lateinit var statRevenue: TextView
    private lateinit var statItems: TextView
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView

    private val chips = mutableListOf<TextView>()
    private val tabs = mutableListOf<TextView>()

    private var period: Period = Period.TODAY
    private var tab: Tab = Tab.ORDERS

    private val priceFormat = NumberFormat.getInstance(Locale("vi", "VN"))
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
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

        ordersAdapter = OrdersAdapter(emptyList()) { showDetail(it) }
        soldAdapter = ProductsSoldAdapter(emptyList())

        chips.add(findViewById<TextView>(R.id.chipToday))
        chips.add(findViewById<TextView>(R.id.chip7))
        chips.add(findViewById<TextView>(R.id.chip30))
        chips.add(findViewById<TextView>(R.id.chipAll))
        chips[0].setOnClickListener { setPeriod(Period.TODAY) }
        chips[1].setOnClickListener { setPeriod(Period.D7) }
        chips[2].setOnClickListener { setPeriod(Period.D30) }
        chips[3].setOnClickListener { setPeriod(Period.ALL) }

        tabs.add(findViewById<TextView>(R.id.tabOrders))
        tabs.add(findViewById<TextView>(R.id.tabProducts))
        tabs[0].setOnClickListener { setTab(Tab.ORDERS) }
        tabs[1].setOnClickListener { setTab(Tab.PRODUCTS) }

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
        refresh()
    }

    private fun setTab(t: Tab) {
        tab = t
        tabs.forEachIndexed { i, v -> v.isSelected = i == t.ordinal }
        list.adapter = if (t == Tab.ORDERS) ordersAdapter else soldAdapter
        refresh()
    }

    private fun rangeFor(p: Period): Pair<String?, String?> {
        val now = System.currentTimeMillis()
        val today = dateFormat.format(Date(now))
        return when (p) {
            Period.TODAY -> today to today
            Period.D7 -> {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
                cal.timeInMillis = now
                cal.add(Calendar.DAY_OF_MONTH, -6)
                dateFormat.format(cal.time) to today
            }
            Period.D30 -> {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"))
                cal.timeInMillis = now
                cal.add(Calendar.DAY_OF_MONTH, -29)
                dateFormat.format(cal.time) to today
            }
            Period.ALL -> null to null
        }
    }

    private fun refresh() {
        val (from, to) = rangeFor(period)
        val s = db.summary(from, to)
        statOrders.text = s.orderCount.toString()
        statRevenue.text = priceFormat.format(s.totalRevenue) + "₫"
        val q = s.totalItems
        statItems.text = if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString()

        if (tab == Tab.ORDERS) {
            val rows = db.queryOrders(from, to, limit = 500)
            ordersAdapter.submit(rows)
            emptyView.text = "Chưa có đơn nào trong khoảng đã chọn"
            emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        } else {
            val rows = db.productsSold(from, to, limit = 100)
            soldAdapter.submit(rows)
            emptyView.text = "Chưa bán sản phẩm nào trong khoảng đã chọn"
            emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
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
            sb.append('\n')
            if (it.unitPrice > 0) {
                sb.append("    ").append(priceFormat.format(it.unitPrice))
                    .append("₫ × ").append(qty)
                    .append(" = ").append(priceFormat.format(it.lineTotal)).append("₫\n")
            } else {
                sb.append("    (chưa map sản phẩm)\n")
            }
        }
        dialog.findViewById<TextView>(R.id.itemsBlock).text = sb.toString().trimEnd()
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
