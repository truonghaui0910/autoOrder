package com.autoorder

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.text.Editable
import android.text.TextWatcher
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ZaloChatsActivity : AppCompatActivity() {

    companion object {
        private val CHAT_TYPES = listOf("normal", "customer", "order", "shipper", "bartender")
        private val STATUSES = listOf("active", "inactive")
        private val SORT_LABELS = listOf(
            "Tin mới nhất",
            "Tin cũ nhất",
            "Tên A→Z",
            "Tên Z→A"
        )
        private val TYPE_FILTER_LABELS = listOf("Tất cả loại", "normal", "customer", "order", "shipper", "bartender")
        private val TYPE_FILTER_VALUES = listOf("", "normal", "customer", "order", "shipper", "bartender")
        private val KIND_FILTER_LABELS = listOf("Tất cả", "Cá nhân", "Group")

        private fun foldText(s: String): String {
            if (s.isEmpty()) return ""
            return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .replace('đ', 'd').replace('Đ', 'd')
                .lowercase()
        }
    }

    private lateinit var adapter: ZaloChatsAdapter
    private lateinit var list: RecyclerView
    private lateinit var headerCount: TextView
    private lateinit var emptyView: View
    private lateinit var etSearch: EditText
    private lateinit var spSort: Spinner
    private lateinit var spChatType: Spinner
    private lateinit var spKind: Spinner
    private lateinit var db: ShopDb

    private var allRows: List<ZaloChat> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zalo_chats)

        db = ShopDb(this)
        list = findViewById(R.id.list)
        headerCount = findViewById(R.id.headerCount)
        emptyView = findViewById(R.id.emptyView)
        etSearch = findViewById(R.id.etSearch)
        spSort = findViewById(R.id.spSort)
        spChatType = findViewById(R.id.spChatType)
        spKind = findViewById(R.id.spKind)

        val refilter = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = applyFilters()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spSort.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, SORT_LABELS
        )
        spSort.onItemSelectedListener = refilter
        spChatType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, TYPE_FILTER_LABELS
        )
        spChatType.onItemSelectedListener = refilter
        spKind.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, KIND_FILTER_LABELS
        )
        spKind.onItemSelectedListener = refilter
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilters()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val sw = resources.configuration.smallestScreenWidthDp
        val cols = when {
            sw >= 900 -> 3
            sw >= 600 -> 2
            else -> 1
        }
        list.layoutManager = GridLayoutManager(this, cols)
        adapter = ZaloChatsAdapter { showEditDialog(it) }
        list.adapter = adapter

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSync).setOnClickListener { syncNow() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        allRows = db.listZaloChats()
        applyFilters()
    }

    private fun applyFilters() {
        val rawQ = etSearch.text.toString().trim()
        val q = foldText(rawQ)
        val qDigits = rawQ.replace(Regex("\\D"), "")
        val typeIdx = spChatType.selectedItemPosition.coerceAtLeast(0)
        val typeVal = TYPE_FILTER_VALUES.getOrNull(typeIdx).orEmpty()
        val kindIdx = spKind.selectedItemPosition.coerceAtLeast(0)
        val filtered = allRows.filter {
            val textMatch = q.isBlank() ||
                foldText(it.name).contains(q) ||
                it.zaloId.lowercase().contains(q) ||
                it.addressList().any { a -> foldText(a).contains(q) } ||
                (qDigits.isNotEmpty() && it.phoneList().any { p ->
                    p.replace(Regex("\\D"), "").contains(qDigits)
                })
            textMatch &&
                (typeVal.isBlank() || it.chatType == typeVal) &&
                (kindIdx == 0 || (kindIdx == 1 && !it.isGroup) || (kindIdx == 2 && it.isGroup))
        }
        val sorted = when (spSort.selectedItemPosition) {
            1 -> filtered.sortedBy { it.lastMsgAt }
            2 -> filtered.sortedBy { it.name.lowercase() }
            3 -> filtered.sortedByDescending { it.name.lowercase() }
            else -> filtered.sortedByDescending { it.lastMsgAt }
        }
        adapter.submit(sorted)
        headerCount.text = if (sorted.size == allRows.size) "${sorted.size} chat"
        else "${sorted.size}/${allRows.size} chat"
        emptyView.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun syncNow() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val tv = TextView(this).apply {
            text = "Số giờ tin nhắn cuối (để trống = chỉ quét những conv đang hiển thị):"
            textSize = 13f
            setTextColor(0xFF455A64.toInt())
        }
        val etHours = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "VD: 24"
            setText("24")
            setSelection(text.length)
        }
        container.addView(tv)
        container.addView(etHours)

        AlertDialog.Builder(this)
            .setTitle("Đồng bộ Zalo chat")
            .setView(container)
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Đồng bộ") { _, _ ->
                val hours = etHours.text.toString().trim().toDoubleOrNull() ?: 0.0
                runSync(hours)
            }
            .show()
    }

    private fun runSync(hours: Double) {
        val started = if (hours > 0) {
            ChatWebActivity.requestScanConvsRecent(hours) {
                runOnUiThread {
                    Toast.makeText(this, "Đồng bộ xong", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
        } else {
            val ok = ChatWebActivity.requestScanConvs()
            if (ok) Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 1500)
            ok
        }
        if (started) {
            Toast.makeText(
                this,
                if (hours > 0) "Đang scroll & đồng bộ ${hours}h gần nhất..."
                else "Đang đồng bộ từ Zalo Web...",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Chat Web chưa mở")
                .setMessage("Cần mở màn Chat (Zalo Web) trước để đồng bộ. Mở ngay?")
                .setNegativeButton("Huỷ", null)
                .setPositiveButton("Mở") { _, _ ->
                    startActivity(Intent(this, ChatWebActivity::class.java))
                }
                .show()
        }
    }

    private fun showEditDialog(chat: ZaloChat) {
        val dialog = Dialog(this, R.style.TransparentDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_zalo_chat_edit)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            val w = (resources.displayMetrics.widthPixels * 0.94).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val etName = dialog.findViewById<EditText>(R.id.etName)
        val txtZaloId = dialog.findViewById<TextView>(R.id.txtZaloId)
        val spChatType = dialog.findViewById<Spinner>(R.id.spChatType)
        val spStatus = dialog.findViewById<Spinner>(R.id.spStatus)
        val etPhone = dialog.findViewById<EditText>(R.id.etPhone)
        val etAddress = dialog.findViewById<EditText>(R.id.etAddress)
        val btnDelete = dialog.findViewById<Button>(R.id.btnDelete)
        val btnSave = dialog.findViewById<Button>(R.id.btnSave)

        etName.setText(chat.name)
        txtZaloId.text = "${chat.zaloId} · ${if (chat.isGroup) "Group" else "Cá nhân"}"

        spChatType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, CHAT_TYPES
        )
        spChatType.setSelection(CHAT_TYPES.indexOf(chat.chatType).coerceAtLeast(0))

        spStatus.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, STATUSES
        )
        spStatus.setSelection(STATUSES.indexOf(chat.status).coerceAtLeast(0))

        etPhone.setText(chat.phone)
        etAddress.setText(chat.address)

        btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Xoá Zalo chat")
                .setMessage("Xoá '${chat.name.ifBlank { chat.zaloId }}' khỏi danh sách?\nLần đồng bộ tới sẽ tự thêm lại nếu Zalo Web vẫn render conv này.")
                .setNegativeButton("Huỷ", null)
                .setPositiveButton("Xoá") { _, _ ->
                    db.deleteZaloChat(chat.id)
                    dialog.dismiss()
                    refresh()
                }
                .show()
        }

        btnSave.setOnClickListener {
            db.updateZaloChat(
                id = chat.id,
                name = etName.text.toString().trim(),
                chatType = CHAT_TYPES[spChatType.selectedItemPosition],
                status = STATUSES[spStatus.selectedItemPosition],
                phone = normalizeMulti(etPhone.text.toString()),
                address = normalizeMulti(etAddress.text.toString())
            )
            dialog.dismiss()
            refresh()
        }

        dialog.findViewById<View>(R.id.btnDismiss).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun normalizeMulti(text: String): String =
        text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    override fun onDestroy() {
        super.onDestroy()
        runCatching { db.close() }
    }
}
