package com.autoorder

import android.app.AlertDialog
import android.content.Context
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
     * Hiển thị confirm dialog khi phát hiện đơn trùng SĐT trong ngày.
     * onConfirm: user vẫn muốn lưu đơn mới.
     * onCancel:  user huỷ.
     */
    fun show(
        ctx: Context,
        newCustomerName: String,
        newPhone: String,
        existing: List<DuplicateOrderInfo>,
        onConfirm: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val nameDisplay = newCustomerName.trim().ifBlank { "(không tên)" }
        val msg = buildString {
            append("SĐT ").append(newPhone.trim()).append(" (").append(nameDisplay).append(") ")
            append("đã có ").append(existing.size).append(" đơn trong hôm nay:\n\n")
            existing.forEachIndexed { idx, o ->
                val whoRaw = o.senderName.ifBlank { o.convName }
                val who = whoRaw.ifBlank { "(không tên)" }
                val t = if (o.createdAt > 0) timeFmt.format(Date(o.createdAt)) else ""
                append("• #").append(o.id)
                if (t.isNotBlank()) append(" · ").append(t)
                append(" — ").append(who)
                if (o.paid) append(" · đã thanh toán")
                append('\n')
                if (o.itemsText.isNotBlank()) {
                    o.itemsText.lines().forEach { line ->
                        if (line.isNotBlank()) append("   ").append(line).append('\n')
                    }
                }
                append("   Tổng: ").append(priceFmt.format(o.totalAmount)).append("₫")
                if (idx < existing.size - 1) append("\n\n") else append('\n')
            }
            append("\nVẫn lưu đơn mới?")
        }
        AlertDialog.Builder(ctx)
            .setTitle("Đơn trùng SĐT trong ngày")
            .setMessage(msg)
            .setPositiveButton("Vẫn lưu") { _, _ -> onConfirm() }
            .setNegativeButton("Huỷ") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
