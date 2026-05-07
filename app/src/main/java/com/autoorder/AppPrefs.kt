package com.autoorder

import android.content.Context

object AppPrefs {
    private const val FILE = "autoorder_prefs"
    private const val KEY_VIEW_MODE = "view_mode"
    private const val KEY_ORDERS_FILTER_COLLAPSED = "orders_filter_collapsed"
    private const val KEY_ORDERS_CHART_MODE = "orders_chart_mode"
    private const val KEY_COPY_WITH_PRICES = "copy_with_prices"

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

    fun isOrdersFilterCollapsed(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ORDERS_FILTER_COLLAPSED, false)

    fun setOrdersFilterCollapsed(ctx: Context, collapsed: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ORDERS_FILTER_COLLAPSED, collapsed).apply()
    }

    fun isOrdersChartMode(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_ORDERS_CHART_MODE, false)

    fun setOrdersChartMode(ctx: Context, chart: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ORDERS_CHART_MODE, chart).apply()
    }

    fun isCopyWithPrices(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_COPY_WITH_PRICES, true)

    fun setCopyWithPrices(ctx: Context, withPrices: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COPY_WITH_PRICES, withPrices).apply()
    }
}
