package com.autoorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class BankAccount(
    val id: String,
    val bankBin: String,
    val bankName: String,
    val accountNumber: String,
    val holder: String,
    val active: Boolean
)

object BankAccountsStore {
    private const val FILE = "autoorder_prefs"
    private const val KEY = "bank_accounts"

    fun list(ctx: Context): List<BankAccount> {
        val raw = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                BankAccount(
                    id = o.optString("id"),
                    bankBin = o.optString("bin"),
                    bankName = o.optString("name"),
                    accountNumber = o.optString("acc"),
                    holder = o.optString("holder"),
                    active = o.optBoolean("active", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun getActive(ctx: Context): BankAccount? =
        list(ctx).firstOrNull { it.active } ?: list(ctx).firstOrNull()

    fun save(ctx: Context, accounts: List<BankAccount>) {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("bin", a.bankBin)
                put("name", a.bankName)
                put("acc", a.accountNumber)
                put("holder", a.holder)
                put("active", a.active)
            })
        }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(ctx: Context, acc: BankAccount) {
        val cur = list(ctx).toMutableList()
        val idx = cur.indexOfFirst { it.id == acc.id }
        if (idx >= 0) cur[idx] = acc else cur.add(acc)
        if (cur.none { it.active }) {
            cur[0] = cur[0].copy(active = true)
        }
        save(ctx, cur)
    }

    fun delete(ctx: Context, id: String) {
        val cur = list(ctx).filter { it.id != id }.toMutableList()
        if (cur.isNotEmpty() && cur.none { it.active }) {
            cur[0] = cur[0].copy(active = true)
        }
        save(ctx, cur)
    }

    fun setActive(ctx: Context, id: String) {
        val cur = list(ctx).map { it.copy(active = it.id == id) }
        save(ctx, cur)
    }

    fun newId(): String = UUID.randomUUID().toString()
}

object Banks {
    data class Bank(val bin: String, val short: String, val full: String)

    val LIST = listOf(
        Bank("970418", "BIDV", "BIDV - Đầu tư & Phát triển VN"),
        Bank("970436", "VCB", "Vietcombank"),
        Bank("970407", "TCB", "Techcombank"),
        Bank("970422", "MB", "MB Bank"),
        Bank("970432", "VPB", "VPBank"),
        Bank("970416", "ACB", "ACB"),
        Bank("970423", "TPB", "TPBank"),
        Bank("970405", "AGRI", "Agribank"),
        Bank("970403", "STB", "Sacombank"),
        Bank("970415", "CTG", "VietinBank"),
        Bank("970437", "HDB", "HDBank"),
        Bank("970431", "EIB", "Eximbank"),
        Bank("970443", "SHB", "SHB"),
        Bank("970440", "SEAB", "SeABank"),
        Bank("970448", "OCB", "OCB"),
        Bank("970426", "MSB", "MSB"),
        Bank("970441", "VIB", "VIB"),
        Bank("970454", "BVB", "BVBank"),
        Bank("970449", "LPB", "LPBank"),
        Bank("970419", "NCB", "NCB"),
        Bank("970412", "PVCB", "PVcomBank"),
        Bank("970425", "ABB", "ABBank"),
        Bank("970433", "VIETBANK", "VietBank"),
        Bank("970428", "NAB", "NamABank"),
        Bank("970427", "VAB", "VietABank"),
        Bank("970406", "DAB", "DongABank"),
    )

    fun byBin(bin: String): Bank? = LIST.firstOrNull { it.bin == bin }
}
