package com.autoorder

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var txtViewMode: TextView
    private lateinit var txtProductsCount: TextView
    private lateinit var txtBankSummary: TextView
    private lateinit var txtZaloChatsCount: TextView
    private lateinit var txtAutoSync: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        txtViewMode = findViewById(R.id.txtViewMode)
        txtProductsCount = findViewById(R.id.txtProductsCount)
        txtBankSummary = findViewById(R.id.txtBankSummary)
        txtZaloChatsCount = findViewById(R.id.txtZaloChatsCount)
        txtAutoSync = findViewById(R.id.txtAutoSync)
        refreshSummaries()

        findViewById<View>(R.id.rowAutoSync).setOnClickListener { showAutoSyncDialog() }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.rowViewMode).setOnClickListener { showViewModeDialog() }
        findViewById<View>(R.id.rowProducts).setOnClickListener {
            startActivity(Intent(this, ProductsActivity::class.java))
        }
        findViewById<View>(R.id.rowBankAccounts).setOnClickListener {
            startActivity(Intent(this, BankAccountsActivity::class.java))
        }
        findViewById<View>(R.id.rowOpenInbox).setOnClickListener {
            startActivity(Intent(this, OrdersActivity::class.java))
        }
        findViewById<View>(R.id.rowZaloChats).setOnClickListener {
            startActivity(Intent(this, ZaloChatsActivity::class.java))
        }

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
        findViewById<View>(R.id.btnSettings).setOnClickListener { /* đang ở đây */ }
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    private fun refreshSummaries() {
        txtViewMode.text = if (AppPrefs.isMobile(this))
            "Mobile view (Giao diện điện thoại)"
        else
            "Web view (Desktop, 3 cột)"
        val count = ShopDb(this).listProducts(activeOnly = false).size
        txtProductsCount.text = "$count sản phẩm"

        val zaloChatsCount = runCatching { MessagesDb(this).countZaloChats() }.getOrDefault(0)
        txtZaloChatsCount.text = "$zaloChatsCount chat"

        val accounts = BankAccountsStore.list(this)
        val active = accounts.firstOrNull { it.active }
        val autoEnabled = AppPrefs.isAutoSyncEnabled(this)
        val autoMin = AppPrefs.getAutoSyncIntervalMin(this)
        txtAutoSync.text = if (autoEnabled) "Bật · mỗi $autoMin phút" else "Tắt"

        txtBankSummary.text = when {
            accounts.isEmpty() -> "Chưa có — đang dùng tài khoản mặc định"
            active != null -> "${active.bankName} · ${active.accountNumber} (${accounts.size} TK)"
            else -> "${accounts.size} tài khoản"
        }
    }

    private fun showAutoSyncDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, 0)
        }
        val sw = Switch(this).apply {
            text = "Bật tự động đồng bộ"
            isChecked = AppPrefs.isAutoSyncEnabled(this@SettingsActivity)
        }
        val tvLabel = TextView(this).apply {
            text = "Khoảng thời gian giữa mỗi lần (phút):"
            textSize = 13f
            setTextColor(0xFF455A64.toInt())
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val etMin = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "VD: 5"
            setText(AppPrefs.getAutoSyncIntervalMin(this@SettingsActivity).toString())
            setSelection(text.length)
        }
        container.addView(sw)
        container.addView(tvLabel)
        container.addView(etMin)

        AlertDialog.Builder(this)
            .setTitle("Tự động đồng bộ")
            .setView(container)
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Lưu") { _, _ ->
                val min = etMin.text.toString().trim().toIntOrNull()?.coerceAtLeast(1)
                    ?: AppPrefs.DEFAULT_AUTO_SYNC_INTERVAL_MIN
                AppPrefs.setAutoSyncEnabled(this, sw.isChecked)
                AppPrefs.setAutoSyncIntervalMin(this, min)
                WebMonitorService.reschedule()
                refreshSummaries()
                Toast.makeText(
                    this,
                    if (sw.isChecked) "Đã bật · mỗi $min phút" else "Đã tắt tự động đồng bộ",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun showViewModeDialog() {
        val dialog = Dialog(this, R.style.TransparentDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_view_mode)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            val w = (resources.displayMetrics.widthPixels * 0.92).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val optWeb = dialog.findViewById<LinearLayout>(R.id.optWeb)
        val optMobile = dialog.findViewById<LinearLayout>(R.id.optMobile)
        val iconWeb = dialog.findViewById<ImageView>(R.id.iconWeb)
        val iconMobile = dialog.findViewById<ImageView>(R.id.iconMobile)

        fun render() {
            val isMobile = AppPrefs.isMobile(this)
            optWeb.isSelected = !isMobile
            optMobile.isSelected = isMobile
            iconWeb.setImageResource(
                if (!isMobile) R.drawable.ic_check_circle else R.drawable.ic_radio_off
            )
            iconMobile.setImageResource(
                if (isMobile) R.drawable.ic_check_circle else R.drawable.ic_radio_off
            )
        }
        render()

        optWeb.setOnClickListener {
            if (AppPrefs.getViewMode(this) != AppPrefs.MODE_WEB) {
                AppPrefs.setViewMode(this, AppPrefs.MODE_WEB)
                Toast.makeText(this, "Đã chuyển sang Web view", Toast.LENGTH_SHORT).show()
            }
            refreshSummaries()
            render()
        }
        optMobile.setOnClickListener {
            if (AppPrefs.getViewMode(this) != AppPrefs.MODE_MOBILE) {
                AppPrefs.setViewMode(this, AppPrefs.MODE_MOBILE)
                Toast.makeText(this, "Đã chuyển sang Mobile view", Toast.LENGTH_SHORT).show()
            }
            refreshSummaries()
            render()
        }

        dialog.findViewById<View>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnDismiss).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
