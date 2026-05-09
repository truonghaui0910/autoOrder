package com.autoorder

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.AdapterView
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
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<View>(R.id.btnHome).setOnClickListener {
            startActivity(Intent(this, ChatWebActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        findViewById<View>(R.id.btnCheckout).setOnClickListener {
            startActivity(Intent(this, ChatWebActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("open_checkout", true))
            finish()
        }
        findViewById<View>(R.id.btnDump).setOnClickListener {
            Toast.makeText(this, "Quét DOM phải làm ở màn Chat", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnInbox).setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
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
        val spCat = dialog.findViewById<Spinner>(R.id.spCategory)
        val etName = dialog.findViewById<EditText>(R.id.etName)
        val etPrice = dialog.findViewById<EditText>(R.id.etPrice)
        val etNote = dialog.findViewById<EditText>(R.id.etNote)
        val swActive = dialog.findViewById<Switch>(R.id.swActive)
        val btnDelete = dialog.findViewById<Button>(R.id.btnDelete)

        val addNewLabel = "+ Thêm danh mục mới..."
        val categories = ArrayList<String>().apply {
            addAll(db.listProducts(activeOnly = false)
                .map { it.category.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted())
            if (existing != null) {
                val cur = existing.category.trim().uppercase()
                if (cur.isNotEmpty() && !contains(cur)) add(cur)
            }
            if (isEmpty()) add("KHÁC")
            add(addNewLabel)
        }
        val catAdapter = ArrayAdapter(this, R.layout.spinner_item_dark, categories)
        catAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark)
        spCat.adapter = catAdapter

        fun selectCategory(value: String) {
            val v = value.trim().uppercase().ifBlank { "KHÁC" }
            var idx = categories.indexOf(v)
            if (idx < 0) {
                categories.add(categories.size - 1, v)
                catAdapter.notifyDataSetChanged()
                idx = categories.indexOf(v)
            }
            spCat.setSelection(idx)
        }

        spCat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (categories[position] == addNewLabel) {
                    val input = EditText(this@ProductsActivity).apply {
                        hint = "VD: TRÀ, ĂN VẶT"
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                        setSingleLine(true)
                    }
                    AlertDialog.Builder(this@ProductsActivity)
                        .setTitle("Danh mục mới")
                        .setView(input)
                        .setNegativeButton("Huỷ") { _, _ -> spCat.setSelection(0) }
                        .setOnCancelListener { spCat.setSelection(0) }
                        .setPositiveButton("OK") { _, _ ->
                            val v = input.text.toString().trim().uppercase()
                            if (v.isEmpty()) {
                                spCat.setSelection(0)
                            } else {
                                selectCategory(v)
                            }
                        }
                        .show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (existing != null) {
            title.text = "Sửa sản phẩm"
            selectCategory(existing.category)
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
            val selCat = (spCat.selectedItem as? String).orEmpty()
            val cat = if (selCat.isBlank() || selCat == addNewLabel) "KHÁC" else selCat
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
