package com.autoorder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BankAccountsAdapter(
    private val onSetActive: (String) -> Unit,
    private val onEdit: (BankAccount) -> Unit
) : RecyclerView.Adapter<BankAccountsAdapter.VH>() {

    private val rows = mutableListOf<BankAccount>()

    fun submit(list: List<BankAccount>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iconActive: ImageView = v.findViewById(R.id.iconActive)
        val txtBankName: TextView = v.findViewById(R.id.txtBankName)
        val txtAccount: TextView = v.findViewById(R.id.txtAccount)
        val txtHolder: TextView = v.findViewById(R.id.txtHolder)
        val btnEdit: ImageView = v.findViewById(R.id.btnEdit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bank_account, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val a = rows[position]
        holder.iconActive.setImageResource(
            if (a.active) R.drawable.ic_check_circle else R.drawable.ic_radio_off
        )
        holder.txtBankName.text = a.bankName
        holder.txtAccount.text = "STK: ${a.accountNumber}"
        holder.txtHolder.text = a.holder
        holder.itemView.setOnClickListener { onSetActive(a.id) }
        holder.btnEdit.setOnClickListener { onEdit(a) }
    }
}
