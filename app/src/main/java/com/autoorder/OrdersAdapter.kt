package com.autoorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OrdersAdapter(
    private var items: List<OrderRecord>,
    private val onClick: (OrderRecord) -> Unit,
    private val onTogglePaid: ((OrderRecord) -> Unit)? = null,
    private val onCheckPayment: ((OrderRecord) -> Unit)? = null
) : RecyclerView.Adapter<OrdersAdapter.VH>() {

    private val priceFormat = NumberFormat.getInstance(Locale("vi", "VN"))
    private val timeFormat = SimpleDateFormat("HH:mm dd/MM", Locale("vi", "VN"))
    private var avatarMap: Map<String, String> = emptyMap()

    fun submit(rows: List<OrderRecord>, avatars: Map<String, String> = emptyMap()) {
        items = rows
        avatarMap = avatars
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val imgAvatar: ImageView = v.findViewById(R.id.imgAvatar)
        val sender: TextView = v.findViewById(R.id.sender)
        val zaloId: TextView = v.findViewById(R.id.zaloId)
        val total: TextView = v.findViewById(R.id.total)
        val meta: TextView = v.findViewById(R.id.meta)
        val preview: TextView = v.findViewById(R.id.preview)
        val paidIcon: ImageView = v.findViewById(R.id.paidIcon)
        val checkPaymentIcon: ImageView = v.findViewById(R.id.checkPaymentIcon)
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

        if (o.zaloId.isNotBlank()) {
            holder.zaloId.text = "ID: ${o.zaloId}"
            holder.zaloId.visibility = View.VISIBLE
        } else {
            holder.zaloId.visibility = View.GONE
        }

        val avatarUrl = avatarMap[o.zaloId].orEmpty()
        if (avatarUrl.isNotBlank()) {
            holder.imgAvatar.load(avatarUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_avatar_placeholder)
                error(R.drawable.bg_avatar_placeholder)
                transformations(CircleCropTransformation())
            }
        } else {
            holder.imgAvatar.setImageDrawable(null)
            holder.imgAvatar.setBackgroundResource(R.drawable.bg_avatar_placeholder)
        }

        bindPaidIcon(holder.paidIcon, o.paid)
        holder.paidIcon.setOnClickListener { onTogglePaid?.invoke(o) }

        holder.checkPaymentIcon.setColorFilter(
            if (o.paid) 0xFF90A4AE.toInt() else 0xFF1E88E5.toInt()
        )
        holder.checkPaymentIcon.setOnClickListener { onCheckPayment?.invoke(o) }

        holder.itemView.setOnClickListener { onClick(o) }
    }

    private fun bindPaidIcon(iv: ImageView, paid: Boolean) {
        if (paid) {
            iv.setImageResource(R.drawable.ic_check_circle)
            iv.setColorFilter(ContextCompat.getColor(iv.context, android.R.color.holo_green_dark))
            iv.contentDescription = "Đã thanh toán"
        } else {
            iv.setImageResource(R.drawable.ic_radio_off)
            iv.setColorFilter(0xFF90A4AE.toInt())
            iv.contentDescription = "Chưa thanh toán"
        }
    }

    override fun getItemCount(): Int = items.size
}
