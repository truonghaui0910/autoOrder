package com.autoorder

import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var txtViewMode: TextView
    private lateinit var txtProductsCount: TextView
    private lateinit var txtBankSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        txtViewMode = findViewById(R.id.txtViewMode)
        txtProductsCount = findViewById(R.id.txtProductsCount)
        txtBankSummary = findViewById(R.id.txtBankSummary)
        refreshSummaries()

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

        findViewById<View>(R.id.btnHome).setOnClickListener {
            startActivity(Intent(this, ChatWebActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        findViewById<View>(R.id.btnReload).setOnClickListener { /* no-op */ }
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

        val accounts = BankAccountsStore.list(this)
        val active = accounts.firstOrNull { it.active }
        txtBankSummary.text = when {
            accounts.isEmpty() -> "Chưa có — đang dùng tài khoản mặc định"
            active != null -> "${active.bankName} · ${active.accountNumber} (${accounts.size} TK)"
            else -> "${accounts.size} tài khoản"
        }
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
