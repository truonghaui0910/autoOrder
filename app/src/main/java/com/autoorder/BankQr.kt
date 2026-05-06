package com.autoorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object BankQr {
    private const val FALLBACK_BIN = "970418"
    private const val FALLBACK_BANK_NAME = "BIDV"
    private const val FALLBACK_ACCOUNT = "962VMS5012819599152"
    private const val FALLBACK_HOLDER = "DANG DINH TUAN"

    private fun resolve(ctx: Context): BankAccount {
        val active = BankAccountsStore.getActive(ctx)
        return active ?: BankAccount(
            id = "fallback",
            bankBin = FALLBACK_BIN,
            bankName = FALLBACK_BANK_NAME,
            accountNumber = FALLBACK_ACCOUNT,
            holder = FALLBACK_HOLDER,
            active = true
        )
    }

    fun infoText(ctx: Context): String {
        val a = resolve(ctx)
        return "${a.bankName} — STK: ${a.accountNumber}\nChủ TK: ${a.holder}"
    }

    fun vietQrUrl(ctx: Context, amount: Long, addInfo: String): String {
        val a = resolve(ctx)
        val accountForUrl = a.accountNumber.filter { !it.isWhitespace() }
        return Uri.Builder()
            .scheme("https")
            .authority("img.vietqr.io")
            .appendPath("image")
            .appendPath("${a.bankBin}-$accountForUrl-compact2.png")
            .appendQueryParameter("amount", amount.toString())
            .appendQueryParameter("addInfo", addInfo)
            .appendQueryParameter("accountName", a.holder)
            .build()
            .toString()
    }

    fun loadAsync(url: String, onResult: (Bitmap?) -> Unit) {
        Thread {
            var bmp: Bitmap? = null
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 12000
                    requestMethod = "GET"
                }
                conn.inputStream.use { bmp = BitmapFactory.decodeStream(it) }
                conn.disconnect()
            } catch (_: Exception) {
            }
            Handler(Looper.getMainLooper()).post { onResult(bmp) }
        }.start()
    }

    fun bitmapToDataUrl(bmp: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$b64"
    }
}
