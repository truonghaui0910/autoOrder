package com.autoorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.NumberFormat
import java.util.Locale

class ProductsAdapter(
    private var items: List<Product>,
    private val onClick: (Product) -> Unit
) : RecyclerView.Adapter<ProductsAdapter.VH>() {

    private val priceFormat = NumberFormat.getInstance(Locale("vi", "VN"))

    fun submit(rows: List<Product>) {
        items = rows
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val category: TextView = v.findViewById(R.id.category)
        val name: TextView = v.findViewById(R.id.name)
        val note: TextView = v.findViewById(R.id.note)
        val price: TextView = v.findViewById(R.id.price)
        val inactive: TextView = v.findViewById(R.id.inactiveChip)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_product, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = items[position]
        holder.category.text = p.category
        holder.name.text = p.name
        holder.price.text = priceFormat.format(p.price) + "₫"
        if (p.note.isBlank()) {
            holder.note.visibility = View.GONE
        } else {
            holder.note.visibility = View.VISIBLE
            holder.note.text = p.note
        }
        holder.inactive.visibility = if (p.active) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener { onClick(p) }
    }

    override fun getItemCount(): Int = items.size
}
