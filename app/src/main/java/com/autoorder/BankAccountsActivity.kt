package com.autoorder

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class BankAccountsActivity : AppCompatActivity() {

    private lateinit var adapter: BankAccountsAdapter
    private lateinit var headerCount: TextView
    private lateinit var emptyView: View
    private lateinit var list: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bank_accounts)

        headerCount = findViewById(R.id.headerCount)
        emptyView = findViewById(R.id.emptyView)
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = BankAccountsAdapter(
            onSetActive = { id ->
                BankAccountsStore.setActive(this, id)
                refresh()
            },
            onEdit = { showEditDialog(it) }
        )
        list.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { showEditDialog(null) }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val rows = BankAccountsStore.list(this)
        adapter.submit(rows)
        headerCount.text = "${rows.size} tài khoản"
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showEditDialog(existing: BankAccount?) {
        val dialog = Dialog(this, R.style.TransparentDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_bank_account_edit)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            val w = (resources.displayMetrics.widthPixels * 0.94).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val title = dialog.findViewById<TextView>(R.id.dialogTitle)
        val spBank = dialog.findViewById<Spinner>(R.id.spBank)
        val etAccount = dialog.findViewById<EditText>(R.id.etAccount)
        val etHolder = dialog.findViewById<EditText>(R.id.etHolder)
        val btnDelete = dialog.findViewById<Button>(R.id.btnDelete)
        val btnSave = dialog.findViewById<Button>(R.id.btnSave)

        title.text = if (existing == null) "Thêm tài khoản" else "Sửa tài khoản"

        val labels = Banks.LIST.map { "${it.short} — ${it.full}" }
        spBank.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        val initialIdx = existing?.let { e ->
            Banks.LIST.indexOfFirst { it.bin == e.bankBin }.takeIf { it >= 0 }
        } ?: 0
        spBank.setSelection(initialIdx)

        etAccount.setText(existing?.accountNumber ?: "")
        etHolder.setText(existing?.holder ?: "")

        btnDelete.visibility = if (existing == null) View.GONE else View.VISIBLE
        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Xoá tài khoản")
                .setMessage("Xoá tài khoản ${existing?.bankName} - ${existing?.accountNumber}?")
                .setNegativeButton("Huỷ", null)
                .setPositiveButton("Xoá") { _, _ ->
                    BankAccountsStore.delete(this, existing!!.id)
                    dialog.dismiss()
                    refresh()
                }
                .show()
        }

        btnSave.setOnClickListener {
            val bank = Banks.LIST[spBank.selectedItemPosition]
            val acc = etAccount.text.toString().trim()
            val holder = etHolder.text.toString().trim().uppercase()
            if (acc.isBlank()) {
                etAccount.error = "Nhập số tài khoản"
                return@setOnClickListener
            }
            if (holder.isBlank()) {
                etHolder.error = "Nhập tên chủ TK"
                return@setOnClickListener
            }
            val saved = BankAccount(
                id = existing?.id ?: BankAccountsStore.newId(),
                bankBin = bank.bin,
                bankName = bank.short,
                accountNumber = acc,
                holder = holder,
                active = existing?.active ?: false
            )
            BankAccountsStore.upsert(this, saved)
            dialog.dismiss()
            refresh()
        }

        dialog.findViewById<View>(R.id.btnDismiss).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
