package com.autoorder

import android.content.Context

object AppPrefs {
    private const val FILE = "autoorder_prefs"
    private const val KEY_VIEW_MODE = "view_mode"

    const val MODE_WEB = "web"
    const val MODE_MOBILE = "mobile"

    fun getViewMode(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_VIEW_MODE, MODE_WEB) ?: MODE_WEB

    fun setViewMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_VIEW_MODE, mode).apply()
    }

    fun isMobile(ctx: Context): Boolean = getViewMode(ctx) == MODE_MOBILE
}
