package com.autoorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessagesAdapter(private var items: List<MessageRow>) :
    RecyclerView.Adapter<MessagesAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("HH:mm dd/MM", Locale("vi", "VN"))

    fun submit(rows: List<MessageRow>) {
        items = rows
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val sender: TextView = v.findViewById(R.id.sender)
        val timestamp: TextView = v.findViewById(R.id.timestamp)
        val content: TextView = v.findViewById(R.id.content)
        val kindChip: TextView = v.findViewById(R.id.kindChip)
        val timeText: TextView = v.findViewById(R.id.timeText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]
        val senderLabel = when {
            r.isSelf -> "Tôi"
            r.senderName.isNotBlank() -> r.senderName
            r.convName.isNotBlank() -> r.convName
            else -> "(không rõ)"
        }
        holder.sender.text = senderLabel
        holder.timestamp.text = dateFormat.format(Date(r.capturedAt))
        holder.content.text = r.content
        holder.kindChip.text = r.kind.ifBlank { "?" }
        holder.timeText.text = if (r.timeText.isBlank()) "" else "Zalo: ${r.timeText}"
    }

    override fun getItemCount(): Int = items.size
}
