package com.autoorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OrdersAdapter(
    private var items: List<OrderRecord>,
    private val onClick: (OrderRecord) -> Unit
) : RecyclerView.Adapter<OrdersAdapter.VH>() {

    private val priceFormat = NumberFormat.getInstance(Locale("vi", "VN"))
    private val timeFormat = SimpleDateFormat("HH:mm dd/MM", Locale("vi", "VN"))

    fun submit(rows: List<OrderRecord>) {
        items = rows
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val sender: TextView = v.findViewById(R.id.sender)
        val total: TextView = v.findViewById(R.id.total)
        val meta: TextView = v.findViewById(R.id.meta)
        val preview: TextView = v.findViewById(R.id.preview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_order, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val o = items[position]
        holder.sender.text = o.senderName.ifBlank { o.convName.ifBlank { "(không tên)" } }
        holder.total.text = priceFormat.format(o.totalAmount) + "₫"
        val parts = mutableListOf<String>()
        parts.add(timeFormat.format(Date(o.createdAt)))
        if (o.phone.isNotBlank()) parts.add(o.phone)
        holder.meta.text = parts.joinToString(" · ")
        holder.preview.text = o.itemsText.replace("\n\nTổng:", " · Tổng:")
        holder.itemView.setOnClickListener { onClick(o) }
    }

    override fun getItemCount(): Int = items.size
}
