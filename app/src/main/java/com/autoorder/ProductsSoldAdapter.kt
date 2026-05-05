package com.autoorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.NumberFormat
import java.util.Locale

class ProductsSoldAdapter(
    private var items: List<ShopDb.ProductSold>
) : RecyclerView.Adapter<ProductsSoldAdapter.VH>() {

    private val priceFormat = NumberFormat.getInstance(Locale("vi", "VN"))

    fun submit(rows: List<ShopDb.ProductSold>) {
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
        val p = items[position]
        holder.name.text = p.productName
        val qtyStr = if (p.totalQty == p.totalQty.toLong().toDouble())
            p.totalQty.toLong().toString()
        else
            p.totalQty.toString()
        holder.qty.text = "Đã bán: $qtyStr"
        holder.revenue.text = priceFormat.format(p.totalRevenue) + "₫"
    }

    override fun getItemCount(): Int = items.size
}
