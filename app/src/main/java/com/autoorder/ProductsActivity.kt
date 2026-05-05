package com.autoorder

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ProductsActivity : AppCompatActivity() {

    private lateinit var db: ShopDb
    private lateinit var adapter: ProductsAdapter
    private lateinit var headerCount: TextView
    private lateinit var emptyView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_products)

        db = ShopDb(this)
        headerCount = findViewById(R.id.headerCount)
        emptyView = findViewById(R.id.emptyView)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = ProductsAdapter(emptyList()) { showEditDialog(it) }
        list.adapter = adapter

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showEditDialog(null) }

        findViewById<View>(R.id.btnHome).setOnClickListener {
            startActivity(Intent(this, ChatWebActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        findViewById<View>(R.id.btnReload).setOnClickListener { refresh() }
        findViewById<View>(R.id.btnDump).setOnClickListener {
            Toast.makeText(this, "Quét DOM phải làm ở màn Chat", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnInbox).setOnClickListener {
            startActivity(Intent(this, MessagesActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val rows = db.listProducts(activeOnly = false)
        adapter.submit(rows)
        headerCount.text = "${rows.size} sản phẩm"
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showEditDialog(existing: Product?) {
        val dialog = Dialog(this, R.style.TransparentDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_product_edit)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            val w = (resources.displayMetrics.widthPixels * 0.94).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val title = dialog.findViewById<TextView>(R.id.dialogTitle)
        val etCat = dialog.findViewById<EditText>(R.id.etCategory)
        val etName = dialog.findViewById<EditText>(R.id.etName)
        val etPrice = dialog.findViewById<EditText>(R.id.etPrice)
        val etNote = dialog.findViewById<EditText>(R.id.etNote)
        val swActive = dialog.findViewById<Switch>(R.id.swActive)
        val btnDelete = dialog.findViewById<Button>(R.id.btnDelete)

        if (existing != null) {
            title.text = "Sửa sản phẩm"
            etCat.setText(existing.category)
            etName.setText(existing.name)
            etPrice.setText(existing.price.toString())
            etNote.setText(existing.note)
            swActive.isChecked = existing.active
            btnDelete.visibility = View.VISIBLE
        } else {
            title.text = "Thêm sản phẩm"
            btnDelete.visibility = View.GONE
        }

        dialog.findViewById<View>(R.id.btnDismiss).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }

        btnDelete.setOnClickListener {
            val id = existing?.id ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Xoá sản phẩm")
                .setMessage("Xoá '${existing.name}'? (Đơn cũ vẫn giữ tên & giá đã chốt)")
                .setNegativeButton("Huỷ", null)
                .setPositiveButton("Xoá") { _, _ ->
                    db.deleteProduct(id)
                    dialog.dismiss()
                    refresh()
                    Toast.makeText(this, "Đã xoá", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        dialog.findViewById<Button>(R.id.btnSave).setOnClickListener {
            val cat = etCat.text.toString().trim().ifBlank { "KHÁC" }
            val name = etName.text.toString().trim()
            val priceStr = etPrice.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Tên không được rỗng", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val price = priceStr.toIntOrNull()
            if (price == null || price < 0) {
                Toast.makeText(this, "Giá không hợp lệ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val p = Product(
                id = existing?.id ?: 0,
                category = cat.uppercase(),
                name = name,
                price = price,
                note = etNote.text.toString().trim(),
                active = swActive.isChecked,
                sortOrder = existing?.sortOrder ?: (System.currentTimeMillis() / 1000).toInt()
            )
            db.upsertProduct(p)
            dialog.dismiss()
            refresh()
            Toast.makeText(this, if (existing == null) "Đã thêm" else "Đã lưu", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }
}
