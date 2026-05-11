package com.autoorder

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import coil.load
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
        var orderNote: String = "",
        var items: MutableList<OrderItem> = mutableListOf(),
        var rawJson: String = "",
        var editedItemsText: String? = null
    )

    private var pendingPeer: String? = null
    private var pendingAnimId: String? = null
    private var pendingAvatarUrl: String? = null
    private var pendingOrder: ParsedOrder? = null
    var onOrderSaved: (() -> Unit)? = null
    var onActiveChanged: ((Boolean) -> Unit)? = null
    private var activeDialog: Dialog? = null

    fun hasActive(): Boolean = activeDialog != null

    fun restore(): Boolean {
        val d = activeDialog ?: return false
        return try {
            d.show()
            true
        } catch (_: Exception) {
            activeDialog = null
            runCatching { onActiveChanged?.invoke(false) }
            false
        }
    }

    fun dismissActive() {
        activeDialog?.let { runCatching { it.dismiss() } }
        activeDialog = null
    }

    data class BatchOrder(
        val messageIndex: Int,
        val customerName: String,
        val address: String,
        val phone: String,
        val orderNote: String,
        val items: List<OrderItem>,
        val rawJson: String,
        val zaloId: String = "",
        val matched: Boolean = false,
        val avatarUrl: String = "",
        val ambiguous: Boolean = false,
        val candidates: List<ZaloChat> = emptyList()
    )

    private const val BATCH_CHUNK_SIZE = 10

    private fun normalizePhone(s: String): String = s.replace(Regex("\\D"), "")

    /**
     * Lọc lại candidates trùng tên Zalo bằng số điện thoại.
     * - Nếu chỉ có 0/1 match thì trả về nguyên list, không lọc.
     * - Nếu nhiều match + có phone từ AI: giữ candidate nào có ÍT NHẤT 1 phone
     *   (sau khi normalize digits) chứa phone đơn (hoặc ngược lại — bidirectional contains).
     * - Nếu sau lọc còn 0 → trả lại list gốc (để UI hiện tất cả cho user chọn).
     */
    private fun pickByPhone(matches: List<ZaloChat>, orderPhone: String): List<ZaloChat> {
        if (matches.size <= 1) return matches
        val pn = normalizePhone(orderPhone)
        if (pn.isBlank()) return matches
        val filtered = matches.filter { zc ->
            ZaloChat.splitMulti(zc.phone)
                .map { normalizePhone(it) }
                .filter { it.isNotBlank() }
                .any { p -> p.contains(pn) || pn.contains(p) }
        }
        return if (filtered.isEmpty()) matches else filtered
    }

    fun analyzeBatchOrders(
        ctx: Context,
        messagesJson: String,
        onProgress: ((List<BatchOrder>, Int, Int) -> Unit)? = null,
        onResult: (Result<List<BatchOrder>>) -> Unit
    ) {
        if (ANTHROPIC_KEY.isBlank()) {
            onResult(Result.failure(RuntimeException("Chưa cấu hình ANTHROPIC_API_KEY trong local.properties.")))
            return
        }
        io.execute {
            val r = runCatching {
                val inputArr = JSONArray(messagesJson)
                val products = ShopDb(ctx).listProducts(activeOnly = true)
                val productsById = products.associateBy { it.id }
                val msgDb = MessagesDb(ctx)
                val accumulated = ArrayList<BatchOrder>()

                val total = inputArr.length()
                val chunkCount = (total + BATCH_CHUNK_SIZE - 1) / BATCH_CHUNK_SIZE
                var chunkIdx = 0
                var i = 0
                while (i < total) {
                    val end = minOf(i + BATCH_CHUNK_SIZE, total)
                    val chunk = JSONArray()
                    for (j in i until end) {
                        chunk.put(inputArr.getJSONObject(j))
                    }
                    chunkIdx++
                    val raw = callClaudeBatch(chunk.toString(), products, productsById)
                    val withZalo = raw.map { o ->
                        val matches = msgDb.getZaloChatsByName(o.customerName)
                        val picked = pickByPhone(matches, o.phone)
                        when {
                            picked.size == 1 -> o.copy(
                                zaloId = picked[0].zaloId,
                                matched = true,
                                avatarUrl = picked[0].avatarUrl
                            )
                            picked.size > 1 -> o.copy(
                                ambiguous = true,
                                candidates = picked
                            )
                            else -> o
                        }
                    }
                    accumulated.addAll(withZalo)
                    if (onProgress != null) {
                        val snapshot = ArrayList(accumulated)
                        val done = chunkIdx
                        main.post { onProgress(snapshot, done, chunkCount) }
                    }
                    i = end
                }
                accumulated.toList()
            }
            main.post { onResult(r) }
        }
    }

    private fun callClaudeBatch(
        messagesJson: String,
        products: List<Product>,
        productsById: Map<Long, Product>
    ): List<BatchOrder> {
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
            Bạn là trợ lý phân tích batch tin nhắn của chủ shop trong nhóm chốt đơn Zalo.
            Mỗi tin nhắn (do chủ shop gửi) chứa thông tin của MỘT đơn hàng từ một khách (đã được chủ shop forward / soạn lại).

            DANH SÁCH SẢN PHẨM CỦA SHOP (JSON):
            $menuJson

            ĐẦU VÀO: mảng JSON các tin nhắn dạng:
            [{"index": 0, "text": "..."}, {"index": 1, "text": "..."}, ...]

            ĐẦU RA: chỉ trả về DUY NHẤT một JSON object, không markdown, không giải thích, schema:
            {
              "orders": [
                {
                  "message_index": integer,        // index của tin nhắn nguồn
                  "customer_name": string,         // tên khách hàng (để khớp với tên hội thoại Zalo)
                  "address": string,
                  "phone": string,
                  "order_note": string,
                  "items": [
                    {
                      "product_id": integer | null,
                      "product_name": string,
                      "quantity": number,
                      "note": string
                    }
                  ]
                }
              ]
            }

            QUY TẮC:
            - Mỗi message_index xuất hiện tối đa 1 lần. Tin nhắn không phải là đơn (chào hỏi, hỏi giá, không có món) → bỏ qua, KHÔNG đưa vào "orders".
            - "customer_name" cố gắng lấy CHÍNH XÁC từ dòng "Tên: ..." nếu có; nếu không có rõ thì trích tên người trong nội dung. Đây sẽ dùng để khớp với tên hội thoại Zalo nên cần giữ nguyên dạng tên người.
            - MAPPING SẢN PHẨM (theo thứ tự ưu tiên):
              1) Khớp CHÍNH XÁC với "name" sản phẩm (không phân biệt hoa/thường, dấu, khoảng trắng) → BẮT BUỘC chọn đúng sản phẩm đó.
              2) Nếu không khớp trực tiếp, mới so khớp theo cụm con / từ khoá đặc trưng. Phân biệt size S/M/L nếu khách nói size.
              3) Khi nhiều ứng viên hợp lý, chọn cái có tên trùng NHIỀU TỪ NHẤT.
              4) Không có sản phẩm phù hợp → "product_id": null, "product_name" giữ nguyên văn khách viết.
            - "product_name" luôn lấy theo tên trong danh sách shop (nếu mapping được).
            - "quantity" mặc định 1 nếu không nói rõ.
            - "note" của item: yêu cầu riêng cho MÓN đó (ít đường, ít đá, không hành...). Để rỗng nếu không có.
            - "order_note" là ghi chú chung của CẢ ĐƠN (giờ giao, lời nhắn). KHÔNG nhồi note món vào đây.
            - Thiếu thông tin (address/phone/order_note) → chuỗi rỗng.
        """.trimIndent()

        val userMsg = "Danh sách tin nhắn:\n$messagesJson"

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 16000)
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
            readTimeout = 120000
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
        val stopReason = root.optString("stop_reason")
        val contentArr = root.getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val block = contentArr.getJSONObject(i)
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        val raw = "{" + sb.toString()
        val jsonText = extractJsonObject(raw) ?: raw
        val parsed = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: salvageBatchJson(raw, stopReason)
            ?: throw RuntimeException("AI response không parse được (stop_reason=$stopReason). Có thể cần giảm số tin / tăng max_tokens.")
        val ordersArr = parsed.optJSONArray("orders") ?: JSONArray()
        val out = ArrayList<BatchOrder>(ordersArr.length())
        for (i in 0 until ordersArr.length()) {
            val o = ordersArr.optJSONObject(i) ?: continue
            val items = ArrayList<OrderItem>()
            val itemsArr = o.optJSONArray("items") ?: JSONArray()
            for (j in 0 until itemsArr.length()) {
                val obj = itemsArr.optJSONObject(j) ?: continue
                val pid = if (obj.isNull("product_id")) null
                    else obj.optLong("product_id").takeIf { v -> v > 0 }
                val product = pid?.let { productsById[it] }
                val name = obj.optString("product_name").ifBlank { product?.name ?: "" }
                if (name.isBlank()) continue
                val rawQty = obj.optDouble("quantity", 1.0)
                val qty = if (rawQty.isNaN() || rawQty <= 0) 1.0 else rawQty
                items.add(
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
            out.add(
                BatchOrder(
                    messageIndex = o.optInt("message_index", -1),
                    customerName = o.optString("customer_name"),
                    address = o.optString("address"),
                    phone = o.optString("phone"),
                    orderNote = o.optString("order_note"),
                    items = items,
                    rawJson = o.toString()
                )
            )
        }
        return out
    }

    fun showManualOrder(ctx: Context, chat: ZaloChat) {
        val order = ParsedOrder(senderName = chat.name)
        showPopup(ctx, chat.zaloId, chat.name, chat.avatarUrl, order)
    }

    fun extractAndShow(ctx: Context, animId: String, peerName: String, avatarUrl: String, messagesJson: String) {
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
                result.onSuccess { showPopup(ctx, animId, peerName, avatarUrl, it) }
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
                "address": string,
                "phone": string,
                "order_note": string,
                "items": [
                  {
                    "product_id": integer | null,
                    "product_name": string,
                    "quantity": number,
                    "note": string
                  }
                ]
              }
            - MAPPING SẢN PHẨM (rất quan trọng, làm THEO THỨ TỰ):
              1) ƯU TIÊN TUYỆT ĐỐI: nếu chuỗi khách viết KHỚP CHÍNH XÁC (không phân biệt hoa/thường, dấu, khoảng trắng thừa) với "name" của một sản phẩm trong danh sách → BẮT BUỘC chọn sản phẩm đó. Không được suy luận sang sản phẩm khác.
                 Ví dụ: khách viết "1 cóc thơm" và danh sách có sản phẩm "Cóc thơm" → PHẢI chọn "Cóc thơm". KHÔNG được chọn "Thơm chanh dây" hay bất kỳ sản phẩm nào khác chỉ vì có chứa từ "thơm".
              2) Nếu không có khớp trực tiếp, mới so khớp theo cụm con / từ khoá đặc trưng:
                 "1 ly trà mãng cầu" → "Mãng cầu" (TRÀ).
                 "chân gà M" → "Chân gà sốt thái M". Phân biệt size S/M/L nếu khách nói size.
              3) Khi có nhiều ứng viên hợp lý, chọn cái có tên trùng NHIỀU TỪ NHẤT với chuỗi khách viết, ưu tiên trùng đầy đủ trước khi đoán nghĩa.
              4) Nếu thật sự không có sản phẩm phù hợp → "product_id": null, "product_name" giữ nguyên văn khách.
            - "product_name" luôn lấy theo tên trong danh sách shop (nếu mapping được), không lấy nguyên văn của khách.
            - "quantity" là số lượng (số nguyên hoặc thập phân nếu nửa con). Nếu khách không nói rõ → 1.
            - "note" của từng item: yêu cầu riêng cho MÓN đó (ít đường, ít đá, không hành...). Để rỗng nếu không có.
            - "order_note" là ghi chú chung cho CẢ ĐƠN, KHÔNG phải cho 1 món riêng lẻ. Ví dụ: giờ giao ("giao trước 5h"), cách giao ("để cổng"), lời nhắn, yêu cầu chung ("gói riêng từng món", "gọi trước khi đến"). Nếu không có thì để chuỗi rỗng. KHÔNG nhồi note của từng món vào đây — note món để ở "items[].note".
            - Nếu khách hỏi giá / chưa chốt món / chỉ chào hỏi → "items": [].
            - Thiếu thông tin nào (address/phone/order_note) thì để chuỗi rỗng.
            - KHÔNG được suy đoán hay trích xuất tên người gửi từ nội dung — tên người gửi do hệ thống tự gán từ Zalo, không phải việc của bạn.
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
            // Tên Zalo BẮT BUỘC lấy từ peerName (parse từ .msg-item trong WebView),
            // KHÔNG dùng bất cứ thứ gì AI trả về cho trường này.
            senderName = peerName,
            address = parsed.optString("address"),
            phone = parsed.optString("phone"),
            orderNote = parsed.optString("order_note"),
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

    private val compactDateFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
    }

    private fun todayCompact(): String = compactDateFormat.format(Date())

    private fun slug(s: String): String {
        val noDiacritics = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace('đ', 'd').replace('Đ', 'D')
        return noDiacritics
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
    }

    /**
     * Khi response bị cắt giữa chừng (max_tokens), cố vớt vát: tìm các order
     * object hoàn chỉnh trong mảng "orders" rồi gói lại thành JSON hợp lệ.
     */
    private fun salvageBatchJson(raw: String, stopReason: String?): JSONObject? {
        val key = "\"orders\""
        val keyIdx = raw.indexOf(key)
        if (keyIdx < 0) return null
        val arrStart = raw.indexOf('[', keyIdx)
        if (arrStart < 0) return null

        val items = ArrayList<String>()
        var i = arrStart + 1
        while (i < raw.length) {
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i >= raw.length) break
            if (raw[i] == ']') break
            if (raw[i] == ',') { i++; continue }
            if (raw[i] != '{') break
            // walk this object respecting strings & escapes
            var depth = 0
            var inStr = false
            var esc = false
            val start = i
            var end = -1
            while (i < raw.length) {
                val c = raw[i]
                if (inStr) {
                    if (esc) esc = false
                    else if (c == '\\') esc = true
                    else if (c == '"') inStr = false
                } else {
                    when (c) {
                        '"' -> inStr = true
                        '{' -> depth++
                        '}' -> { depth--; if (depth == 0) { end = i; break } }
                    }
                }
                i++
            }
            if (end < 0) break  // truncated mid-object → bỏ
            items.add(raw.substring(start, end + 1))
            i = end + 1
        }
        if (items.isEmpty()) return null
        val rebuilt = "{\"orders\":[" + items.joinToString(",") + "],\"_truncated\":true,\"_stop_reason\":${JSONObject.quote(stopReason ?: "")}}"
        return runCatching { JSONObject(rebuilt) }.getOrNull()
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

    internal fun formatQty(q: Double): String =
        if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString()

    internal fun formatLineTotal(it: OrderItem): String =
        if (it.unitPrice > 0) priceFormat.format(it.lineTotal) + "₫" else "(chưa map)"

    internal fun buildItemsText(
        items: List<OrderItem>,
        withPrices: Boolean = true,
        productsById: Map<Long, Product>? = null
    ): String {
        if (items.isEmpty()) return ""
        val sb = StringBuilder()
        items.forEachIndexed { idx, it ->
            if (idx > 0) sb.append('\n')
            sb.append(formatQty(it.quantity)).append(" x ").append(it.productName)
            if (it.note.isNotBlank()) sb.append(" (").append(it.note).append(")")
            if (withPrices) {
                if (it.unitPrice > 0) {
                    sb.append(" — ").append(priceFormat.format(it.lineTotal)).append("₫")
                } else {
                    sb.append(" — (chưa map sản phẩm)")
                }
            }
        }
        val total = items.sumOf { it.lineTotal }
        if (total > 0) {
            sb.append("\n\nTổng: ").append(priceFormat.format(total)).append("₫")
            if (productsById != null) {
                val byCat = LinkedHashMap<String, Double>()
                items.forEach { oi ->
                    val cat = oi.productId?.let { productsById[it]?.category }
                        ?.takeIf { c -> c.isNotBlank() } ?: "Khác"
                    byCat[cat] = (byCat[cat] ?: 0.0) + oi.quantity
                }
                if (byCat.isNotEmpty()) {
                    val parts = byCat.entries.joinToString(", ") {
                        "${it.key}: ${formatQty(it.value)}"
                    }
                    sb.append(" (").append(parts).append(")")
                }
            }
        }
        return sb.toString()
    }

    internal fun simpleWatch(after: (String) -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            after(s?.toString() ?: "")
        }
    }

    private fun showPopup(ctx: Context, animId: String, peerName: String, avatarUrl: String, order: ParsedOrder) {
        activeDialog?.let { runCatching { it.dismiss() } }
        activeDialog = null
        pendingPeer = peerName
        pendingAnimId = animId
        pendingAvatarUrl = avatarUrl
        pendingOrder = order

        val zaloChat = if (animId.isNotBlank())
            runCatching { MessagesDb(ctx).getZaloChatByZaloId(animId) }.getOrNull() else null
        if (order.address.isBlank()) zaloChat?.addressList()?.firstOrNull()?.let { order.address = it }
        if (order.phone.isBlank()) zaloChat?.phoneList()?.firstOrNull()?.let { order.phone = it }

        val products = ShopDb(ctx).listProducts(activeOnly = true)
        val productsById = products.associateBy { it.id }

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_order, null, false)
        val etName = view.findViewById<EditText>(R.id.etName)
        val etAddr = view.findViewById<EditText>(R.id.etAddr)
        val etPhone = view.findViewById<EditText>(R.id.etPhone)
        val etOrderNote = view.findViewById<EditText>(R.id.etOrderNote)
        val itemsContainer = view.findViewById<LinearLayout>(R.id.itemsContainer)
        val itemsEmpty = view.findViewById<View>(R.id.itemsEmpty)
        val txtGrandTotal = view.findViewById<android.widget.TextView>(R.id.txtGrandTotal)
        val txtTotalLabel = view.findViewById<android.widget.TextView>(R.id.txtTotalLabel)
        val btnAddItem = view.findViewById<Button>(R.id.btnAddItem)
        val qrImage = view.findViewById<ImageView>(R.id.qrImage)
        val qrStatus = view.findViewById<android.widget.TextView>(R.id.qrStatus)
        val copyContent = view.findViewById<android.widget.TextView>(R.id.copyContent)
        view.findViewById<android.widget.TextView>(R.id.bankInfo).text = BankQr.infoText(ctx)
        val imgAvatar = view.findViewById<ImageView>(R.id.imgAvatar)
        if (avatarUrl.isNotBlank()) {
            imgAvatar.load(avatarUrl) {
                crossfade(true)
                placeholder(R.drawable.bg_avatar_placeholder)
                error(R.drawable.bg_avatar_placeholder)
                transformations(coil.transform.CircleCropTransformation())
            }
        }
        val chkCustomer = view.findViewById<android.widget.CheckBox>(R.id.chkCustomer)
        chkCustomer.isEnabled = animId.isNotBlank()
        chkCustomer.isChecked = zaloChat?.chatType == "customer"
        chkCustomer.setOnCheckedChangeListener { _, isChecked ->
            if (animId.isBlank()) return@setOnCheckedChangeListener
            val newType = if (isChecked) "customer" else "normal"
            io.execute {
                runCatching {
                    MessagesDb(ctx).setChatTypeByZaloId(
                        animId, newType,
                        fallbackName = peerName,
                        fallbackAvatarUrl = avatarUrl,
                        fallbackIsGroup = animId.startsWith("g")
                    )
                }
            }
        }
        val phoneSavedScroll = view.findViewById<View>(R.id.phoneSavedScroll)
        val phoneSavedRow = view.findViewById<LinearLayout>(R.id.phoneSavedRow)
        val addrSavedScroll = view.findViewById<View>(R.id.addrSavedScroll)
        val addrSavedRow = view.findViewById<LinearLayout>(R.id.addrSavedRow)
        val qrHolder = arrayOfNulls<android.graphics.Bitmap>(1)
        var qrLoadedAmount = -1L

        etName.setText(order.senderName)
        etAddr.setText(order.address)
        etPhone.setText(order.phone)
        etOrderNote.setText(order.orderNote)

        val savedPhones = mutableListOf<String>().apply {
            zaloChat?.phoneList()?.let { addAll(it) }
        }
        val savedAddrs = mutableListOf<String>().apply {
            zaloChat?.addressList()?.let { addAll(it) }
        }
        val density = ctx.resources.displayMetrics.density

        fun makeChip(text: String, onClick: () -> Unit, isSave: Boolean = false): android.widget.TextView {
            val tv = android.widget.TextView(ctx)
            tv.text = text
            tv.textSize = 12f
            tv.setTextColor(if (isSave) 0xFF1E88E5.toInt() else 0xFF37474F.toInt())
            val padH = (10 * density).toInt()
            val padV = (5 * density).toInt()
            tv.setPadding(padH, padV, padH, padV)
            tv.setBackgroundResource(
                if (isSave) R.drawable.bg_chip_customer else R.drawable.bg_chip_normal
            )
            tv.isClickable = true
            tv.isFocusable = true
            tv.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * density).toInt()
            tv.layoutParams = lp
            return tv
        }

        fun renderSavedRow(
            row: LinearLayout, scroll: View,
            saved: List<String>, current: String,
            target: EditText, onSaveNew: (String) -> Unit
        ) {
            row.removeAllViews()
            val cur = current.trim()
            saved.forEach { v ->
                row.addView(makeChip(v, { target.setText(v) }))
            }
            val isNew = cur.isNotEmpty() && saved.none { it.equals(cur, ignoreCase = true) }
            if (isNew) {
                row.addView(makeChip("+ Lưu \"${cur.take(24)}${if (cur.length > 24) "…" else ""}\"",
                    { onSaveNew(cur) }, isSave = true))
            }
            scroll.visibility = if (row.childCount > 0) View.VISIBLE else View.GONE
        }

        lateinit var refreshPhone: () -> Unit
        lateinit var refreshAddr: () -> Unit
        refreshPhone = {
            renderSavedRow(phoneSavedRow, phoneSavedScroll, savedPhones,
                etPhone.text.toString(), etPhone) { v ->
                if (animId.isNotBlank() && MessagesDb(ctx).appendPhoneToZaloChat(animId, v)) {
                    savedPhones.add(v)
                    android.widget.Toast.makeText(ctx, "Đã lưu SĐT", android.widget.Toast.LENGTH_SHORT).show()
                }
                refreshPhone()
            }
        }
        refreshAddr = {
            renderSavedRow(addrSavedRow, addrSavedScroll, savedAddrs,
                etAddr.text.toString(), etAddr) { v ->
                if (animId.isNotBlank() && MessagesDb(ctx).appendAddressToZaloChat(animId, v)) {
                    savedAddrs.add(v)
                    android.widget.Toast.makeText(ctx, "Đã lưu địa chỉ", android.widget.Toast.LENGTH_SHORT).show()
                }
                refreshAddr()
            }
        }
        val chkCopyWithPrices = view.findViewById<android.widget.CheckBox>(R.id.chkCopyWithPrices)
        fun refreshCopyContent() {
            val name = etName.text.toString()
            val phone = etPhone.text.toString()
            val addr = etAddr.text.toString()
            val note = etOrderNote.text.toString()
            val withPrices = chkCopyWithPrices.isChecked
            copyContent.text = buildOrderText(
                name,
                buildItemsText(order.items, withPrices, productsById),
                phone, addr, note
            )
        }
        val btnClearNote = view.findViewById<ImageView>(R.id.btnClearNote)
        fun updateNoteClear() {
            btnClearNote.visibility =
                if (etOrderNote.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        btnClearNote.setOnClickListener { etOrderNote.setText("") }
        etName.addTextChangedListener(simpleWatch { refreshCopyContent() })
        etPhone.addTextChangedListener(simpleWatch { refreshPhone(); refreshCopyContent() })
        etAddr.addTextChangedListener(simpleWatch { refreshAddr(); refreshCopyContent() })
        etOrderNote.addTextChangedListener(simpleWatch { refreshCopyContent(); updateNoteClear() })
        updateNoteClear()
        refreshPhone()
        refreshAddr()

        val dialog = Dialog(ctx, R.style.TransparentDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0x00000000))
            val w = (ctx.resources.displayMetrics.widthPixels * 0.94).toInt()
            val h = (ctx.resources.displayMetrics.heightPixels * 0.88).toInt()
            setLayout(w, h)
        }

        val qrReloadRunnable = Runnable {
            val total = order.items.sumOf { it.lineTotal }
            if (total <= 0) {
                qrImage.setImageDrawable(null)
                qrStatus.visibility = View.VISIBLE
                qrStatus.text = "Chưa có tổng để tạo QR"
                qrHolder[0] = null
                qrLoadedAmount = -1
                return@Runnable
            }
            if (total == qrLoadedAmount) return@Runnable
            qrLoadedAmount = total
            qrStatus.visibility = View.VISIBLE
            qrStatus.text = "Đang tạo QR..."
            val url = BankQr.vietQrUrl(ctx, total, "MCZUOXQV2HS4LNP 504618370")
            BankQr.loadAsync(url) { bmp ->
                if (qrLoadedAmount != total) return@loadAsync
                if (bmp != null) {
                    qrImage.setImageBitmap(bmp)
                    qrStatus.visibility = View.GONE
                    qrHolder[0] = bmp
                } else {
                    qrStatus.text = "Không tải được QR"
                }
            }
        }
        fun reloadQrDebounced() {
            main.removeCallbacks(qrReloadRunnable)
            main.postDelayed(qrReloadRunnable, 350L)
        }

        fun updateGrandTotal() {
            val total = order.items.sumOf { it.lineTotal }
            val totalQty = order.items.sumOf { it.quantity }
            txtGrandTotal.text = priceFormat.format(total) + "₫"
            txtTotalLabel.text = "Tổng (${formatQty(totalQty)} món)"
            itemsEmpty.visibility = if (order.items.isEmpty()) View.VISIBLE else View.GONE
            reloadQrDebounced()
            refreshCopyContent()
        }

        lateinit var renderAll: () -> Unit
        var applyTransparency: (() -> Unit)? = null

        fun bindRow(row: View, idx: Int) {
            val tvProduct = row.findViewById<android.widget.TextView>(R.id.tvProduct)
            val etQty = row.findViewById<EditText>(R.id.etQty)
            val etNote = row.findViewById<EditText>(R.id.etNote)
            val tvLineTotal = row.findViewById<android.widget.TextView>(R.id.tvLineTotal)
            val btnMinus = row.findViewById<View>(R.id.btnMinus)
            val btnPlus = row.findViewById<View>(R.id.btnPlus)
            val btnDelete = row.findViewById<View>(R.id.btnDelete)

            val item = order.items[idx]
            tvProduct.text = when {
                item.productName.isBlank() -> "(chọn sản phẩm)"
                item.productId == null -> "(off-menu) ${item.productName}"
                else -> item.productName
            }

            etQty.setText(formatQty(item.quantity))
            etNote.setText(item.note)
            tvLineTotal.text = formatLineTotal(item)

            val qtyWatcher = simpleWatch { s ->
                val cur = order.items.getOrNull(idx) ?: return@simpleWatch
                val newQty = s.replace(",", ".").toDoubleOrNull()
                if (newQty != null && newQty > 0) {
                    order.items[idx] = cur.copy(quantity = newQty)
                    tvLineTotal.text = formatLineTotal(order.items[idx])
                    updateGrandTotal()
                }
            }
            val noteWatcher = simpleWatch { s ->
                val cur = order.items.getOrNull(idx) ?: return@simpleWatch
                order.items[idx] = cur.copy(note = s)
            }
            etQty.tag = qtyWatcher
            etNote.tag = noteWatcher
            etQty.addTextChangedListener(qtyWatcher)
            etNote.addTextChangedListener(noteWatcher)

            fun bumpQty(delta: Double) {
                val cur = order.items.getOrNull(idx) ?: return
                val newQty = (cur.quantity + delta).coerceAtLeast(1.0)
                order.items[idx] = cur.copy(quantity = newQty)
                etQty.removeTextChangedListener(qtyWatcher)
                etQty.setText(formatQty(newQty))
                etQty.addTextChangedListener(qtyWatcher)
                tvLineTotal.text = formatLineTotal(order.items[idx])
                updateGrandTotal()
            }
            btnMinus.setOnClickListener { bumpQty(-1.0) }
            btnPlus.setOnClickListener { bumpQty(+1.0) }

            btnDelete.setOnClickListener {
                if (idx < order.items.size) {
                    order.items.removeAt(idx)
                    renderAll()
                }
            }

            tvProduct.setOnClickListener {
                openProductPicker(ctx, products) { picked ->
                    val cur = order.items.getOrNull(idx) ?: return@openProductPicker
                    order.items[idx] = cur.copy(
                        productId = picked.id,
                        productName = picked.name,
                        unitPrice = picked.price
                    )
                    tvProduct.text = picked.name
                    tvLineTotal.text = formatLineTotal(order.items[idx])
                    updateGrandTotal()
                }
            }
        }

        renderAll = {
            itemsContainer.removeAllViews()
            order.items.forEachIndexed { idx, _ ->
                val row = LayoutInflater.from(ctx).inflate(
                    R.layout.item_order_row, itemsContainer, false
                )
                itemsContainer.addView(row)
                bindRow(row, idx)
            }
            updateGrandTotal()
            applyTransparency?.invoke()
        }
        renderAll()

        btnAddItem.setOnClickListener {
            order.items.add(
                OrderItem(
                    productId = null,
                    productName = "",
                    quantity = 1.0,
                    unitPrice = 0,
                    note = "",
                    rawText = ""
                )
            )
            renderAll()
            val newIdx = order.items.size - 1
            openProductPicker(ctx, products) { picked ->
                val cur = order.items.getOrNull(newIdx) ?: return@openProductPicker
                order.items[newIdx] = cur.copy(
                    productId = picked.id,
                    productName = picked.name,
                    unitPrice = picked.price
                )
                renderAll()
            }
        }

        fun captureContact() {
            order.senderName = etName.text.toString()
            order.address = etAddr.text.toString()
            order.phone = etPhone.text.toString()
            order.orderNote = etOrderNote.text.toString()
        }

        view.findViewById<View>(R.id.btnDismiss).setOnClickListener {
            pendingPeer = null
            pendingAnimId = null
            pendingAvatarUrl = null
            pendingOrder = null
            dialog.dismiss()
        }

        val btnTransparent = view.findViewById<android.widget.ImageView>(R.id.btnTransparent)
        val transparentState = booleanArrayOf(false)
        val rootView = view as android.view.ViewGroup
        val originalRootBg = rootView.background
        val originalHeaderBg = rootView.getChildAt(0).background
        val keepVisibleIds = setOf(R.id.etName, R.id.etAddr, R.id.etPhone, R.id.etOrderNote, R.id.totalRow, R.id.itemsContainer)
        val textBgColor = 0xE6FFFFFF.toInt()
        fun bg(resId: Int) = androidx.core.content.ContextCompat.getDrawable(ctx, resId)
        fun applyTextBgSpan(tv: android.widget.TextView, on: Boolean) {
            if (tv.text !is android.text.Spannable) {
                tv.setText(tv.text, android.widget.TextView.BufferType.SPANNABLE)
            }
            val sp = tv.text as? android.text.Spannable ?: return
            sp.getSpans(0, sp.length, android.text.style.BackgroundColorSpan::class.java)
                .forEach { sp.removeSpan(it) }
            if (on && sp.isNotEmpty()) {
                sp.setSpan(
                    android.text.style.BackgroundColorSpan(textBgColor),
                    0, sp.length,
                    android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE
                )
            }
        }
        fun ensureBgSpanWatcher(tv: android.widget.TextView) {
            if (tv.getTag(R.id.btnTransparent) != null) return
            if (tv.text !is android.text.Spannable) {
                tv.setText(tv.text, android.widget.TextView.BufferType.SPANNABLE)
            }
            val watcher = object : android.text.TextWatcher {
                private var inside = false
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (inside) return
                    inside = true
                    applyTextBgSpan(tv, transparentState[0])
                    inside = false
                }
            }
            tv.addTextChangedListener(watcher)
            tv.setTag(R.id.btnTransparent, watcher)
        }
        applyTransparency = {
            val on = transparentState[0]
            rootView.background = if (on) null else originalRootBg
            for (i in 0 until rootView.childCount) {
                val child = rootView.getChildAt(i)
                when {
                    i == 0 && child is android.view.ViewGroup -> {
                        child.background = if (on) null else originalHeaderBg
                        for (j in 0 until child.childCount) {
                            val hc = child.getChildAt(j)
                            if (hc.id != R.id.btnTransparent) hc.alpha = if (on) 0f else 1f
                        }
                    }
                    child is android.widget.ScrollView && child.childCount > 0 -> {
                        val inner = child.getChildAt(0) as? android.view.ViewGroup ?: continue
                        for (j in 0 until inner.childCount) {
                            val c = inner.getChildAt(j)
                            when {
                                c.id == R.id.itemsContainer -> {
                                    val rows = c as android.view.ViewGroup
                                    for (k in 0 until rows.childCount) {
                                        val row = rows.getChildAt(k) as? android.view.ViewGroup ?: continue
                                        row.background = if (on) null else bg(R.drawable.bg_input_field)
                                        row.findViewById<View>(R.id.btnDelete).alpha = if (on) 0f else 1f
                                        row.findViewById<View>(R.id.btnMinus).alpha = if (on) 0f else 1f
                                        row.findViewById<View>(R.id.btnPlus).alpha = if (on) 0f else 1f
                                        row.findViewById<View>(R.id.etNote).alpha = if (on) 0f else 1f
                                        val tvProduct = row.findViewById<android.widget.TextView>(R.id.tvProduct)
                                        val tvLineTotal = row.findViewById<android.widget.TextView>(R.id.tvLineTotal)
                                        val etQty = row.findViewById<android.widget.EditText>(R.id.etQty)
                                        if (on) {
                                            tvProduct.background = null
                                            tvLineTotal.background = null
                                            etQty.background = null
                                            tvProduct.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                                        } else {
                                            tvProduct.background = null
                                            tvLineTotal.background = null
                                            etQty.background = bg(R.drawable.bg_qty_field)
                                            tvProduct.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_right, 0)
                                        }
                                        ensureBgSpanWatcher(tvProduct)
                                        ensureBgSpanWatcher(tvLineTotal)
                                        ensureBgSpanWatcher(etQty)
                                        applyTextBgSpan(tvProduct, on)
                                        applyTextBgSpan(tvLineTotal, on)
                                        applyTextBgSpan(etQty, on)
                                    }
                                }
                                c.id == R.id.totalRow && c is android.view.ViewGroup -> {
                                    c.alpha = 1f
                                    for (m in 0 until c.childCount) {
                                        val tv = c.getChildAt(m)
                                        tv.background = null
                                        if (tv is android.widget.TextView) {
                                            ensureBgSpanWatcher(tv)
                                            applyTextBgSpan(tv, on)
                                        }
                                    }
                                }
                                c.id in keepVisibleIds -> {
                                    c.alpha = 1f
                                    c.background = if (on) null else bg(R.drawable.bg_input_field)
                                    if (c is android.widget.TextView) {
                                        ensureBgSpanWatcher(c)
                                        applyTextBgSpan(c, on)
                                    }
                                }
                                else -> c.alpha = if (on) 0f else 1f
                            }
                        }
                    }
                    else -> child.alpha = if (on) 0f else 1f
                }
            }
        }
        btnTransparent.setOnClickListener {
            transparentState[0] = !transparentState[0]
            applyTransparency?.invoke()
            btnTransparent.setImageResource(
                if (transparentState[0]) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            )
        }

        view.findViewById<View>(R.id.btnMinimize).setOnClickListener {
            captureContact()
            dialog.hide()
        }

        chkCopyWithPrices.isChecked = AppPrefs.isCopyWithPrices(ctx)
        chkCopyWithPrices.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setCopyWithPrices(ctx, isChecked)
            refreshCopyContent()
        }

        view.findViewById<Button>(R.id.btnCopy).setOnClickListener {
            captureContact()
            val withPrices = chkCopyWithPrices.isChecked
            val orderText = buildOrderText(order.senderName,
                buildItemsText(order.items, withPrices, productsById),
                order.phone, order.address, order.orderNote)
            copyTextOnly(ctx, orderText)
            qrHolder[0]?.let { PendingQr.dataUrl = BankQr.bitmapToDataUrl(it) }
            android.widget.Toast.makeText(
                ctx, "Đã copy đơn", android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        val btnSave = view.findViewById<Button>(R.id.btnSave)
        btnSave.setOnClickListener {
            val validItems = order.items.filter { it.productName.isNotBlank() }
            if (validItems.isEmpty()) {
                android.widget.Toast.makeText(
                    ctx, "Đơn không có món nào để lưu", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false
            captureContact()
            val itemsText = buildItemsText(validItems)
            val withPrices = chkCopyWithPrices.isChecked
            val copyItemsText = buildItemsText(validItems, withPrices, productsById)
            val orderText = buildOrderText(
                order.senderName, copyItemsText, order.phone, order.address, order.orderNote
            )
            copyTextOnly(ctx, orderText)
            qrHolder[0]?.let { PendingQr.dataUrl = BankQr.bitmapToDataUrl(it) }

            val now = System.currentTimeMillis()
            val orderDate = orderDateFormat.format(Date(now))
            val total = validItems.sumOf { it.lineTotal }
            val record = OrderRecord(
                createdAt = now,
                orderDate = orderDate,
                convName = peerName,
                senderName = order.senderName.trim(),
                phone = order.phone.trim(),
                address = order.address.trim(),
                itemsText = itemsText,
                rawJson = order.rawJson,
                totalAmount = total,
                note = order.orderNote.trim(),
                zaloId = animId,
                orderCode = OrderRecord.makeCode(animId, orderDate, total)
            )
            val newId = runCatching { ShopDb(ctx).insertOrderWithDedup(record, validItems) }
            newId.onSuccess { (id, isNew) ->
                val msg = if (isNew) "Đã lưu đơn #$id & copy vào clipboard"
                          else "Đơn đã tồn tại (#$id). Đã copy vào clipboard"
                Log.i(TAG, "ORDER ${if (isNew) "saved" else "duplicate"} id=$id code=${record.orderCode} total=${record.totalAmount} items=${validItems.size}")
                android.widget.Toast.makeText(
                    ctx, msg, android.widget.Toast.LENGTH_SHORT
                ).show()
                pendingPeer = null
                pendingAnimId = null
                pendingOrder = null
                dialog.dismiss()
                runCatching { onOrderSaved?.invoke() }
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

        dialog.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
                runCatching { onActiveChanged?.invoke(false) }
            }
        }
        activeDialog = dialog
        runCatching { onActiveChanged?.invoke(true) }
        dialog.show()
    }

    internal fun openProductPicker(
        ctx: Context, products: List<Product>, onPick: (Product) -> Unit
    ) {
        if (products.isEmpty()) {
            android.widget.Toast.makeText(
                ctx, "Chưa có sản phẩm nào trong DB. Vào Cài đặt → Sản phẩm để thêm.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }
        val labels = products.map { p ->
            "${p.category} · ${p.name} — ${priceFormat.format(p.price)}₫"
        }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Chọn sản phẩm")
            .setItems(labels) { _, which -> onPick(products[which]) }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun buildOrderText(
        name: String, itemsText: String, phone: String, addr: String, note: String = ""
    ): String = buildString {
        append("Tên: ").append(name).append("\n\n")
        if (note.isNotBlank()) {
            append("Ghi chú: ").append(note.trim()).append("\n\n")
        }
        append(itemsText).append("\n\n")
        append("SĐT: ").append(phone).append('\n')
        append("Địa chỉ: ").append(addr)
    }

    private fun copyTextOnly(ctx: Context, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Đơn hàng", text))
    }

    private fun copyToClipboard(
        ctx: Context, name: String, itemsText: String, phone: String, addr: String
    ) {
        copyTextOnly(ctx, buildOrderText(name, itemsText, phone, addr))
    }

}
