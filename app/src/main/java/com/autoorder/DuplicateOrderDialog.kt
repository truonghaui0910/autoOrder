package com.autoorder

import android.app.AlertDialog
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DuplicateOrderDialog {

    private val priceFmt: NumberFormat = NumberFormat.getInstance(Locale("vi", "VN"))
    private val timeFmt: SimpleDateFormat = SimpleDateFormat("HH:mm", Locale("vi", "VN")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    }

    /**
     * onCreateNew: tạo thêm đơn mới song song.
     * onUpdateExisting(id): ghi đè đơn cũ. Chỉ enable khi `existing.size == 1`.
     */
    fun show(
        ctx: Context,
        newCustomerName: String,
        newPhone: String,
        existing: List<DuplicateOrderInfo>,
        onCreateNew: () -> Unit,
        onUpdateExisting: ((Long) -> Unit)? = null,
        onCancel: () -> Unit = {}
    ) {
        val inflater = LayoutInflater.from(ctx)
        val root = inflater.inflate(R.layout.dialog_duplicate_order, null, false)
        val tvHeader = root.findViewById<TextView>(R.id.tvHeader)
        val cards = root.findViewById<LinearLayout>(R.id.cardsContainer)
        val tvFooter = root.findViewById<TextView>(R.id.tvFooter)

        tvHeader.text = buildHeader(newCustomerName, newPhone, existing.size)

        existing.forEach { o ->
            val card = inflater.inflate(R.layout.dialog_duplicate_order_card, cards, false)
            val tvTitle = card.findViewById<TextView>(R.id.tvTitle)
            val tvPaid = card.findViewById<TextView>(R.id.tvPaid)
            val tvCode = card.findViewById<TextView>(R.id.tvCode)
            val tvItems = card.findViewById<TextView>(R.id.tvItems)
            val tvTotal = card.findViewById<TextView>(R.id.tvTotal)

            val who = o.senderName.ifBlank { o.convName }.ifBlank { "(không tên)" }
            val t = if (o.createdAt > 0) timeFmt.format(Date(o.createdAt)) else ""
            tvTitle.text = buildString {
                append("#").append(o.id)
                if (t.isNotBlank()) append(" · ").append(t)
                append(" · ").append(who)
            }

            if (o.paid) {
                tvPaid.text = "ĐÃ TT"
                tvPaid.setTextColor(0xFF2E7D32.toInt())
                tvPaid.setBackgroundResource(R.drawable.bg_dup_paid_badge)
                tvPaid.visibility = android.view.View.VISIBLE
            } else {
                tvPaid.visibility = android.view.View.GONE
            }

            if (o.orderCode.isNotBlank()) {
                tvCode.text = "Mã: ${o.orderCode}"
                tvCode.visibility = android.view.View.VISIBLE
            } else {
                tvCode.visibility = android.view.View.GONE
            }

            val itemLines = o.itemsText.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("Tổng:") }
            tvItems.text = if (itemLines.isEmpty()) "(không có món)"
            else itemLines.joinToString("\n") { "• $it" }

            tvTotal.text = "Tổng: ${priceFmt.format(o.totalAmount)}₫"
            cards.addView(card)
        }

        tvFooter.text = if (existing.size == 1 && onUpdateExisting != null)
            "Tạo đơn mới song song hay cập nhật đơn cũ?"
        else
            "Vẫn tạo thêm đơn mới?"

        val builder = AlertDialog.Builder(ctx)
            .setTitle("Đơn trùng SĐT trong ngày")
            .setView(root)
            .setPositiveButton("Tạo đơn mới") { _, _ -> onCreateNew() }
            .setNegativeButton("Huỷ") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
        if (existing.size == 1 && onUpdateExisting != null) {
            val oldId = existing[0].id
            builder.setNeutralButton("Cập nhật #$oldId") { _, _ -> onUpdateExisting(oldId) }
        }
        builder.show()
    }

    private fun buildHeader(name: String, phone: String, count: Int): CharSequence {
        val display = name.trim().ifBlank { "(không tên)" }
        val sb = SpannableStringBuilder()
        sb.append("SĐT ")
        val phoneStart = sb.length
        sb.append(phone.trim())
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), phoneStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(0xFF1E88E5.toInt()), phoneStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" (").append(display).append(")\n")
        sb.append("đã có ")
        val cStart = sb.length
        sb.append(count.toString())
        sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), cStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(" đơn trong hôm nay:")
        return sb
    }
}
