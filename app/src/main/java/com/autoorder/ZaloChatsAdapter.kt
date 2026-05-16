package com.autoorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

class ZaloChatsAdapter(
    private val onClick: (ZaloChat) -> Unit
) : RecyclerView.Adapter<ZaloChatsAdapter.VH>() {

    private val items = ArrayList<ZaloChat>()

    fun submit(rows: List<ZaloChat>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_zalo_chat, parent, false)
        return VH(v, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View, private val onClick: (ZaloChat) -> Unit) : RecyclerView.ViewHolder(v) {
        private val imgAvatar: ImageView = v.findViewById(R.id.imgAvatar)
        private val txtName: TextView = v.findViewById(R.id.txtName)
        private val txtZaloId: TextView = v.findViewById(R.id.txtZaloId)
        private val txtTime: TextView = v.findViewById(R.id.txtTime)
        private val txtKind: TextView = v.findViewById(R.id.txtKind)
        private val txtChatType: TextView = v.findViewById(R.id.txtChatType)
        private val txtStatus: TextView = v.findViewById(R.id.txtStatus)
        private val txtPhone: TextView = v.findViewById(R.id.txtPhone)
        private val txtAddress: TextView = v.findViewById(R.id.txtAddress)
        private var current: ZaloChat? = null

        init {
            v.isClickable = true
            v.isFocusable = true
            v.setOnClickListener { current?.let(onClick) }
        }

        fun bind(c: ZaloChat) {
            current = c
            txtName.text = c.name.ifBlank { "(không tên)" }
            txtTime.text = c.lastMsgText
            txtTime.visibility = if (c.lastMsgText.isBlank()) View.GONE else View.VISIBLE
            txtZaloId.text = "ID: ${c.zaloId}"
            txtKind.text = if (c.isGroup) "Group" else "Cá nhân"
            txtKind.setBackgroundResource(
                if (c.isGroup) R.drawable.bg_chip_group else R.drawable.bg_chip_personal
            )
            txtChatType.text = c.chatType
            txtChatType.setBackgroundResource(
                when (c.chatType) {
                    "customer" -> R.drawable.bg_chip_customer
                    "order" -> R.drawable.bg_chip_order
                    "shipper" -> R.drawable.bg_chip_shipper
                    "bartender" -> R.drawable.bg_chip_bartender
                    else -> R.drawable.bg_chip_normal
                }
            )
            txtStatus.text = c.status
            txtStatus.setBackgroundResource(
                if (c.status == "active") R.drawable.bg_chip_active
                else R.drawable.bg_chip_inactive
            )

            val phones = c.phoneList()
            txtPhone.visibility = if (phones.isEmpty()) View.GONE else View.VISIBLE
            txtPhone.text = "SĐT: " + phones.joinToString(" · ")

            val addresses = c.addressList()
            txtAddress.visibility = if (addresses.isEmpty()) View.GONE else View.VISIBLE
            txtAddress.text = "Địa chỉ: " + addresses.joinToString(" · ")

            if (c.avatarUrl.isBlank()) {
                imgAvatar.setImageDrawable(null)
                imgAvatar.setBackgroundResource(R.drawable.bg_avatar_placeholder)
            } else {
                imgAvatar.load(c.avatarUrl) {
                    crossfade(true)
                    placeholder(R.drawable.bg_avatar_placeholder)
                    error(R.drawable.bg_avatar_placeholder)
                    transformations(CircleCropTransformation())
                }
            }
        }
    }
}
