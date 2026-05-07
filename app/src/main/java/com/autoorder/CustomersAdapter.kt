package com.autoorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.NumberFormat
import java.util.Locale

class CustomersAdapter(
    private var items: List<ShopDb.CustomerStat>
) : RecyclerView.Adapter<CustomersAdapter.VH>() {

    private val priceFormat = NumberFormat.getInstance(Locale("vi", "VN"))

    fun submit(rows: List<ShopDb.CustomerStat>) {
        items = rows
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val qty: TextView = v.findViewById(R.id.qty)
        val revenue: TextView = v.findViewById(R.id.revenue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_product_sold, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        holder.name.text = c.displayName
        val parts = mutableListOf("${c.orderCount} đơn")
        if (c.phone.isNotBlank()) parts.add(c.phone)
        holder.qty.text = parts.joinToString(" · ")
        holder.revenue.text = priceFormat.format(c.totalRevenue) + "₫"
    }

    override fun getItemCount(): Int = items.size
}
