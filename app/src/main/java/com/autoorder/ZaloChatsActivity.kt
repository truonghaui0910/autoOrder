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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ZaloChatsActivity : AppCompatActivity() {

    companion object {
        private val CHAT_TYPES = listOf("normal", "customer", "order")
        private val STATUSES = listOf("active", "inactive")
    }

    private lateinit var adapter: ZaloChatsAdapter
    private lateinit var list: RecyclerView
    private lateinit var headerCount: TextView
    private lateinit var emptyView: View
    private lateinit var db: MessagesDb

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zalo_chats)

        db = MessagesDb(this)
        list = findViewById(R.id.list)
        headerCount = findViewById(R.id.headerCount)
        emptyView = findViewById(R.id.emptyView)

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
        val rows = db.listZaloChats()
        adapter.submit(rows)
        headerCount.text = "${rows.size} chat"
        emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun syncNow() {
        if (ChatWebActivity.requestScanConvs()) {
            Toast.makeText(this, "Đang đồng bộ từ Zalo Web...", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 1500)
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
