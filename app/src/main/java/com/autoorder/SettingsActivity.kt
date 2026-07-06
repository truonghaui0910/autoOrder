package com.autoorder

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private lateinit var txtAiSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        txtViewMode = findViewById(R.id.txtViewMode)
        txtProductsCount = findViewById(R.id.txtProductsCount)
        txtBankSummary = findViewById(R.id.txtBankSummary)
        txtZaloChatsCount = findViewById(R.id.txtZaloChatsCount)
        txtAutoSync = findViewById(R.id.txtAutoSync)
        txtAiSummary = findViewById(R.id.txtAiSummary)
        refreshSummaries()

        findViewById<View>(R.id.rowAutoSync).setOnClickListener { showAutoSyncDialog() }
        findViewById<View>(R.id.rowAi).setOnClickListener { showAiDialog() }

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
        findViewById<View>(R.id.rowExportDb).setOnClickListener { exportDbToDownloads() }
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

        val zaloChatsCount = runCatching { ShopDb(this).countZaloChats() }.getOrDefault(0)
        txtZaloChatsCount.text = "$zaloChatsCount chat"

        val accounts = BankAccountsStore.list(this)
        val active = accounts.firstOrNull { it.active }
        val autoEnabled = AppPrefs.isAutoSyncEnabled(this)
        val autoMin = AppPrefs.getAutoSyncIntervalMin(this)
        txtAutoSync.text = if (autoEnabled) "Bật · mỗi $autoMin phút" else "Tắt"

        val provider = AppPrefs.getAiProvider(this)
        val model = AppPrefs.getAiModel(this, provider)
        txtAiSummary.text = "${AppPrefs.providerLabel(provider)} · $model"

        txtBankSummary.text = when {
            accounts.isEmpty() -> "Chưa có — đang dùng tài khoản mặc định"
            active != null -> "${active.bankName} · ${active.accountNumber} (${accounts.size} TK)"
            else -> "${accounts.size} tài khoản"
        }
    }

    private fun exportDbToDownloads() {
        // Pre-Q cần WRITE_EXTERNAL_STORAGE; Q+ dùng MediaStore không cần xin
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_EXPORT_DB
                )
                return
            }
        }

        val result = runCatching {
            ShopDb(this).use { h ->
                h.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val src = getDatabasePath(ShopDb.DB_NAME)
            if (!src.exists()) throw IllegalStateException("Không tìm thấy DB nào")
            writeToDownloads(src, "shop_$ts.db")
        }
        result.onSuccess { paths ->
            Toast.makeText(this, "Đã xuất:\n$paths", Toast.LENGTH_LONG).show()
        }.onFailure { e ->
            Toast.makeText(this, "Xuất DB lỗi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun writeToDownloads(src: File, outName: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, outName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("Không tạo được file ở Downloads")
            resolver.openOutputStream(uri)!!.use { os ->
                FileInputStream(src).use { it.copyTo(os) }
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return "Downloads/$outName"
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val dst = File(dir, outName)
        FileInputStream(src).use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
        return dst.absolutePath
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_EXPORT_DB) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                exportDbToDownloads()
            } else {
                Toast.makeText(this, "Cần quyền lưu trữ để xuất DB", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQ_EXPORT_DB = 4011
    }

    private fun showAiDialog() {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        val curProvider = AppPrefs.getAiProvider(this)
        val rg = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val providers = listOf(
            AppPrefs.AI_CLAUDE to "Claude",
            AppPrefs.AI_OPENROUTER to "OpenRouter",
            AppPrefs.AI_OPENAI to "OpenAI"
        )
        val rbMap = HashMap<String, android.widget.RadioButton>()
        val idToProvider = HashMap<Int, String>()
        providers.forEach { (pid, label) ->
            val rb = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                val lp = android.widget.RadioGroup.LayoutParams(
                    android.widget.RadioGroup.LayoutParams.WRAP_CONTENT,
                    android.widget.RadioGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = (12 * density).toInt() }
                layoutParams = lp
            }
            rbMap[pid] = rb
            idToProvider[rb.id] = pid
            rg.addView(rb)
        }
        rbMap[curProvider]?.let { rg.check(it.id) }
        container.addView(TextView(this).apply {
            text = "Provider"
            textSize = 13f
            setTextColor(0xFF455A64.toInt())
        })
        container.addView(rg)

        val tvModelLabel = TextView(this).apply {
            text = "Model"
            textSize = 13f
            setTextColor(0xFF455A64.toInt())
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val etModel = EditText(this).apply { setSingleLine(true) }
        val tvKeyLabel = TextView(this).apply {
            text = "API key"
            textSize = 13f
            setTextColor(0xFF455A64.toInt())
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val etKey = EditText(this).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val tvHint = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF78909C.toInt())
            setPadding(0, (4 * density).toInt(), 0, 0)
        }

        container.addView(tvModelLabel)
        container.addView(etModel)
        container.addView(tvKeyLabel)
        container.addView(etKey)
        container.addView(tvHint)

        var shown = curProvider

        fun loadFor(provider: String) {
            etModel.hint = AppPrefs.defaultModelOf(provider)
            etModel.setText(AppPrefs.defaultModelOf(provider))
            etKey.setText(AppPrefs.getAiKey(this, provider))
            tvHint.text = "Để trống model để dùng mặc định. Key trống sẽ dùng key build-in (nếu có)."
        }

        fun saveCurrentFields() {
            val m = etModel.text.toString().trim()
            AppPrefs.setAiModel(this, shown,
                if (m.isBlank()) AppPrefs.defaultModelOf(shown) else m)
            AppPrefs.setAiKey(this, shown, etKey.text.toString())
        }

        loadFor(curProvider)

        rg.setOnCheckedChangeListener { _, checkedId ->
            val next = idToProvider[checkedId] ?: return@setOnCheckedChangeListener
            if (next == shown) return@setOnCheckedChangeListener
            saveCurrentFields()
            shown = next
            loadFor(next)
        }

        AlertDialog.Builder(this)
            .setTitle("Cấu hình AI")
            .setView(container)
            .setNegativeButton("Huỷ", null)
            .setPositiveButton("Lưu") { _, _ ->
                saveCurrentFields()
                AppPrefs.setAiProvider(this, shown)
                refreshSummaries()
                Toast.makeText(
                    this,
                    "Đã lưu: ${AppPrefs.providerLabel(shown)} · ${AppPrefs.getAiModel(this, shown)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
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
