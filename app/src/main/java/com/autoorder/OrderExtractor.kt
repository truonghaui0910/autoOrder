package com.autoorder

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

object OrderExtractor {

    private const val TAG = "AutoOrder"
    private val ANTHROPIC_KEY: String = BuildConfig.ANTHROPIC_API_KEY
    private const val MODEL = "claude-haiku-4-5-20251001"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val priceFormat = NumberFormat.getInstance(Locale("vi", "VN"))
    private val orderDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    }

    data class ParsedOrder(
        var senderName: String = "",
        var address: String = "",
        var phone: String = "",
        var items: MutableList<OrderItem> = mutableListOf(),
        var rawJson: String = ""
    )

    fun extractAndShow(ctx: Context, peerName: String, messagesJson: String) {
        if (ANTHROPIC_KEY.isBlank()) {
            main.post {
                AlertDialog.Builder(ctx)
                    .setTitle("Thiếu API key")
                    .setMessage("Chưa cấu hình ANTHROPIC_API_KEY trong local.properties.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            return
        }
        val transcript = buildTranscript(peerName, messagesJson)
        if (transcript.isBlank()) {
            main.post {
                AlertDialog.Builder(ctx)
                    .setTitle("Không có nội dung")
                    .setMessage("Hội thoại đang chọn không có tin nhắn để trích xuất.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            return
        }

        val shopDb = ShopDb(ctx)
        val products = shopDb.listProducts(activeOnly = true)
        val productsById = products.associateBy { it.id }

        val loading = run {
            val pad = (24 * ctx.resources.displayMetrics.density).toInt()
            val progress = android.widget.ProgressBar(ctx).apply { isIndeterminate = true }
            val wrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(pad, pad, pad, pad)
                addView(
                    progress,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            val d = AlertDialog.Builder(ctx)
                .setView(wrap)
                .setCancelable(false)
                .create()
            d.window?.setBackgroundDrawable(ColorDrawable(0x00000000))
            main.post { d.show() }
            d
        }
        io.execute {
            val result = runCatching { callClaude(transcript, peerName, products, productsById) }
            main.post {
                runCatching { loading.dismiss() }
                result.onSuccess { showPopup(ctx, peerName, it) }
                    .onFailure {
                        Log.e(TAG, "Claude fail", it)
                        AlertDialog.Builder(ctx)
                            .setTitle("Lỗi gọi Claude")
                            .setMessage(it.message ?: "Unknown")
                            .setPositiveButton("OK", null)
                            .show()
                    }
            }
        }
    }

    private fun buildTranscript(peerName: String, messagesJson: String): String {
        val arr = runCatching { JSONArray(messagesJson) }.getOrNull() ?: return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val from = o.optString("from")
            val text = o.optString("text").trim()
            if (text.isEmpty()) continue
            val who = if (from == "me") "Tôi (chủ shop)" else (peerName.ifBlank { "Khách" })
            sb.append(who).append(": ").append(text).append('\n')
        }
        return sb.toString().trim()
    }

    private fun callClaude(
        transcript: String,
        peerName: String,
        products: List<Product>,
        productsById: Map<Long, Product>
    ): ParsedOrder {
        val menuJson = JSONArray().apply {
            products.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("category", p.category)
                    put("name", p.name)
                    put("price", p.price)
                    if (p.note.isNotBlank()) put("note", p.note)
                })
            }
        }.toString()

        val sys = """
            Bạn là trợ lý phân tích hội thoại Zalo giữa chủ shop và khách hàng. Trích xuất đơn hàng từ đoạn chat.

            DANH SÁCH SẢN PHẨM CỦA SHOP (JSON):
            $menuJson

            QUY TẮC:
            - Chỉ trả về DUY NHẤT một JSON object, không markdown, không giải thích.
            - Schema:
              {
                "sender_name": string,
                "address": string,
                "phone": string,
                "items": [
                  {
                    "product_id": integer | null,
                    "product_name": string,
                    "quantity": number,
                    "note": string
                  }
                ]
              }
            - Với mỗi món khách order, BẮT BUỘC mapping với một sản phẩm trong DANH SÁCH ở trên qua "id" → đặt vào "product_id".
              Ví dụ: "1 ly trà mãng cầu" → tìm sản phẩm "Mãng cầu" trong category TRÀ, dùng id của nó.
              "chân gà M" → mapping với "Chân gà sốt thái M".
              Phân biệt size S/M/L cho chân gà nếu khách nói size.
            - Nếu món khách order KHÔNG có trong danh sách (off-menu) → "product_id": null, vẫn ghi tên ở "product_name".
            - "product_name" luôn lấy theo tên trong danh sách shop (nếu mapping được), không lấy nguyên văn của khách.
            - "quantity" là số lượng (số nguyên hoặc thập phân nếu nửa con). Nếu khách không nói rõ → 1.
            - "note" chứa yêu cầu đặc biệt: ít đường, ít đá, không hành, ghi chú riêng. Để rỗng nếu không có.
            - Nếu khách hỏi giá / chưa chốt món / chỉ chào hỏi → "items": [].
            - Thiếu thông tin nào (sender_name/address/phone) thì để chuỗi rỗng.
            - Nếu trong chat không có tên khách rõ ràng, dùng tên peer "$peerName" cho sender_name.
        """.trimIndent()

        val userMsg = "Tên peer: $peerName\n\nHội thoại:\n$transcript"

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 1500)
            put("temperature", 0)
            put("system", sys)
            put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "user").put("content", userMsg))
                    .put(JSONObject().put("role", "assistant").put("content", "{"))
            )
        }.toString()

        val url = URL(ENDPOINT)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30000
            readTimeout = 60000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-api-key", ANTHROPIC_KEY)
            setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${resp.take(500)}")

        val root = JSONObject(resp)
        val contentArr = root.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val block = contentArr.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        val raw = "{" + sb.toString()
        val jsonText = extractJsonObject(raw) ?: raw
        val parsed = JSONObject(jsonText)

        val out = ParsedOrder(
            senderName = parsed.optString("sender_name").ifBlank { peerName },
            address = parsed.optString("address"),
            phone = parsed.optString("phone"),
            rawJson = jsonText
        )
        val arr = parsed.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val pid = if (obj.isNull("product_id")) null
                else obj.optLong("product_id").takeIf { v -> v > 0 }
            val product = pid?.let { productsById[it] }
            val name = obj.optString("product_name").ifBlank { product?.name ?: "" }
            if (name.isBlank()) continue
            val rawQty = obj.optDouble("quantity", 1.0)
            val qty = if (rawQty.isNaN() || rawQty <= 0) 1.0 else rawQty
            out.items.add(
                OrderItem(
                    productId = product?.id,
                    productName = name,
                    quantity = qty,
                    unitPrice = product?.price ?: 0,
                    note = obj.optString("note"),
                    rawText = ""
                )
            )
        }
        return out
    }

    private fun extractJsonObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
                }
            }
        }
        return null
    }

    private fun formatItemsText(items: List<OrderItem>): String {
        if (items.isEmpty()) return ""
        val sb = StringBuilder()
        items.forEachIndexed { idx, it ->
            if (idx > 0) sb.append('\n')
            val qty = if (it.quantity == it.quantity.toLong().toDouble())
                it.quantity.toLong().toString()
            else
                it.quantity.toString()
            sb.append(qty).append(" x ").append(it.productName)
            if (it.note.isNotBlank()) sb.append(" (").append(it.note).append(")")
            if (it.unitPrice > 0) {
                sb.append(" — ").append(priceFormat.format(it.lineTotal)).append("₫")
            } else {
                sb.append(" — (chưa map sản phẩm)")
            }
        }
        val total = items.sumOf { it.lineTotal }
        if (total > 0) sb.append("\n\nTổng: ").append(priceFormat.format(total)).append("₫")
        return sb.toString()
    }

    private fun showPopup(ctx: Context, peerName: String, order: ParsedOrder) {
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_order, null, false)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etItems = view.findViewById<EditText>(R.id.etItems)
        val etAddr = view.findViewById<EditText>(R.id.etAddr)
        val etPhone = view.findViewById<EditText>(R.id.etPhone)
        etName.setText(order.senderName)
        etItems.setText(formatItemsText(order.items))
        etAddr.setText(order.address)
        etPhone.setText(order.phone)

        val dialog = Dialog(ctx, R.style.TransparentDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            val w = (ctx.resources.displayMetrics.widthPixels * 0.94).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<android.view.View>(R.id.btnDismiss).setOnClickListener { dialog.dismiss() }

        view.findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val text = buildString {
                append("Tên: ").append(etName.text).append("\n\n")
                append(etItems.text).append("\n\n")
                append("SĐT: ").append(etPhone.text).append('\n')
                append("Địa chỉ: ").append(etAddr.text)
            }
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Đơn hàng", text))
            android.widget.Toast.makeText(
                ctx, "Đã copy đơn hàng", android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        val btnSave = view.findViewById<Button>(R.id.btnSave)
        btnSave.setOnClickListener {
            if (order.items.isEmpty()) {
                android.widget.Toast.makeText(
                    ctx, "Đơn không có món nào để lưu", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false
            val now = System.currentTimeMillis()
            val record = OrderRecord(
                createdAt = now,
                orderDate = orderDateFormat.format(Date(now)),
                convName = peerName,
                senderName = etName.text.toString().trim(),
                phone = etPhone.text.toString().trim(),
                address = etAddr.text.toString().trim(),
                itemsText = etItems.text.toString().trim(),
                rawJson = order.rawJson,
                totalAmount = order.items.sumOf { it.lineTotal }
            )
            val newId = runCatching { ShopDb(ctx).insertOrder(record, order.items) }
            newId.onSuccess { id ->
                Log.i(TAG, "ORDER saved id=$id total=${record.totalAmount} items=${order.items.size}")
                android.widget.Toast.makeText(
                    ctx, "Đã lưu đơn hàng #$id", android.widget.Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }.onFailure {
                Log.e(TAG, "save order fail", it)
                btnSave.isEnabled = true
                AlertDialog.Builder(ctx)
                    .setTitle("Lỗi lưu đơn")
                    .setMessage(it.message ?: "Unknown")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        dialog.show()
    }
}
