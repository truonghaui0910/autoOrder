package com.autoorder

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object OrderExtractor {

    private const val TAG = "AutoOrder"
    private val ANTHROPIC_KEY: String = BuildConfig.ANTHROPIC_API_KEY
    private const val MODEL = "claude-haiku-4-5-20251001"
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class Order(
        var senderName: String = "",
        var items: String = "",
        var address: String = "",
        var phone: String = ""
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
        val loading = run {
            val pad = (24 * ctx.resources.displayMetrics.density).toInt()
            val progress = android.widget.ProgressBar(ctx).apply {
                isIndeterminate = true
            }
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
            d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
            main.post { d.show() }
            d
        }
        io.execute {
            val result = runCatching { callOpenAI(transcript, peerName) }
            main.post {
                runCatching { loading.dismiss() }
                result.onSuccess { showPopup(ctx, it) }
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

    private fun callOpenAI(transcript: String, peerName: String): Order {
        val sys =
            "Bạn là trợ lý phân tích hội thoại Zalo giữa chủ shop và khách hàng. " +
                "Trích xuất đơn hàng từ đoạn chat. Trả về DUY NHẤT một JSON object với các khóa: " +
                "sender_name (tên khách đặt hàng, nếu không có dùng tên peer được cung cấp), " +
                "items (chuỗi liệt kê các món/sản phẩm khách order, mỗi món xuống dòng nếu nhiều), " +
                "address (địa chỉ nhận hàng), " +
                "phone (số điện thoại). " +
                "Nếu thiếu thông tin nào thì để chuỗi rỗng. KHÔNG thêm giải thích, KHÔNG bọc trong markdown code fence."

        val userMsg =
            "Tên peer: $peerName\n\nHội thoại:\n$transcript"

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 1024)
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
        return Order(
            senderName = parsed.optString("sender_name").ifBlank { peerName },
            items = parsed.optString("items"),
            address = parsed.optString("address"),
            phone = parsed.optString("phone")
        )
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

    private fun showPopup(ctx: Context, order: Order) {
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        fun addField(label: String, value: String, multiline: Boolean): EditText {
            val tv = TextView(ctx).apply {
                text = label
                setPadding(0, pad / 2, 0, 4)
            }
            val et = EditText(ctx).apply {
                setText(value)
                if (multiline) {
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    isSingleLine = false
                    minLines = 2
                    setHorizontallyScrolling(false)
                }
            }
            container.addView(tv)
            container.addView(
                et,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return et
        }

        val etName = addField("Tên người gửi", order.senderName, false)
        val etItems = addField("Các món order", order.items, true)
        val etAddr = addField("Địa chỉ nhận hàng", order.address, true)
        val etPhone = addField("Số điện thoại", order.phone, false).apply {
            inputType = InputType.TYPE_CLASS_PHONE
        }

        val scroll = ScrollView(ctx).apply { addView(container) }

        val dlg = AlertDialog.Builder(ctx)
            .setTitle("Đơn hàng trích xuất")
            .setView(scroll)
            .setPositiveButton("Lưu") { _, _ ->
                Log.i(
                    TAG,
                    "ORDER name='${etName.text}' phone='${etPhone.text}' " +
                        "addr='${etAddr.text}' items='${etItems.text}'"
                )
            }
            .setNeutralButton("Copy", null)
            .setNegativeButton("Hủy", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val text = buildString {
                    append("Tên: ").append(etName.text).append("\n\n")
                    append(etItems.text).append("\n\n")
                    append("SĐT: ").append(etPhone.text).append('\n')
                    append("Địa chỉ: ").append(etAddr.text)
                }
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Đơn hàng", text))
                android.widget.Toast.makeText(ctx, "Đã copy đơn hàng", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        dlg.show()
    }
}
