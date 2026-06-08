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

    private const val KEY_ORDERS_PERIOD = "orders_period"
    private const val KEY_ORDERS_PAID_FILTER = "orders_paid_filter"
    private const val KEY_ORDERS_TAB = "orders_tab"
    private const val KEY_ORDERS_SEARCH = "orders_search"
    private const val KEY_ORDERS_CUSTOM_FROM = "orders_custom_from"
    private const val KEY_ORDERS_CUSTOM_TO = "orders_custom_to"

    fun getOrdersPeriod(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ORDERS_PERIOD, "TODAY") ?: "TODAY"

    fun setOrdersPeriod(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDERS_PERIOD, value).apply()
    }

    fun getOrdersPaidFilter(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ORDERS_PAID_FILTER, "ALL") ?: "ALL"

    fun setOrdersPaidFilter(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDERS_PAID_FILTER, value).apply()
    }

    fun getOrdersTab(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ORDERS_TAB, "ORDERS") ?: "ORDERS"

    fun setOrdersTab(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDERS_TAB, value).apply()
    }

    fun getOrdersSearch(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_ORDERS_SEARCH, "") ?: ""

    fun setOrdersSearch(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORDERS_SEARCH, value).apply()
    }

    fun getOrdersCustomRange(ctx: Context): Pair<String?, String?> {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getString(KEY_ORDERS_CUSTOM_FROM, null) to
            sp.getString(KEY_ORDERS_CUSTOM_TO, null)
    }

    fun setOrdersCustomRange(ctx: Context, from: String?, to: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ORDERS_CUSTOM_FROM, from)
            .putString(KEY_ORDERS_CUSTOM_TO, to)
            .apply()
    }

    private const val KEY_AUTO_SYNC_ENABLED = "auto_sync_enabled"
    private const val KEY_AUTO_SYNC_INTERVAL_MIN = "auto_sync_interval_min"
    const val DEFAULT_AUTO_SYNC_INTERVAL_MIN = 5

    fun isAutoSyncEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SYNC_ENABLED, true)

    fun setAutoSyncEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_SYNC_ENABLED, enabled).apply()
    }

    fun getAutoSyncIntervalMin(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_AUTO_SYNC_INTERVAL_MIN, DEFAULT_AUTO_SYNC_INTERVAL_MIN)
            .coerceAtLeast(1)

    fun setAutoSyncIntervalMin(ctx: Context, minutes: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_AUTO_SYNC_INTERVAL_MIN, minutes.coerceAtLeast(1)).apply()
    }

    fun isCopyWithPrices(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_COPY_WITH_PRICES, true)

    fun setCopyWithPrices(ctx: Context, withPrices: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COPY_WITH_PRICES, withPrices).apply()
    }

    // ---- AI provider ----
    const val AI_CLAUDE = "CLAUDE"
    const val AI_OPENROUTER = "OPENROUTER"
    const val AI_OPENAI = "OPENAI"

    const val DEFAULT_MODEL_CLAUDE = "claude-haiku-4-5-20251001"
    const val DEFAULT_MODEL_OPENROUTER = "openai/gpt-5-mini"
    const val DEFAULT_MODEL_OPENAI = "gpt-5-mini"

    private const val KEY_AI_PROVIDER = "ai_provider"

    fun getAiProvider(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_AI_PROVIDER, AI_CLAUDE) ?: AI_CLAUDE

    fun setAiProvider(ctx: Context, provider: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_AI_PROVIDER, provider).apply()
    }

    fun defaultModelOf(provider: String): String = when (provider) {
        AI_OPENROUTER -> DEFAULT_MODEL_OPENROUTER
        AI_OPENAI -> DEFAULT_MODEL_OPENAI
        else -> DEFAULT_MODEL_CLAUDE
    }

    fun providerLabel(provider: String): String = when (provider) {
        AI_OPENROUTER -> "OpenRouter"
        AI_OPENAI -> "OpenAI"
        else -> "Claude"
    }

    private fun modelKey(provider: String) = "ai_model_${provider.lowercase()}"
    private fun apiKeyKey(provider: String) = "ai_key_${provider.lowercase()}"

    fun getAiModel(ctx: Context, provider: String): String {
        val v = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(modelKey(provider), null)
        return if (v.isNullOrBlank()) defaultModelOf(provider) else v
    }

    fun setAiModel(ctx: Context, provider: String, model: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(modelKey(provider), model.trim()).apply()
    }

    fun getAiKey(ctx: Context, provider: String): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(apiKeyKey(provider), "") ?: ""

    fun setAiKey(ctx: Context, provider: String, key: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(apiKeyKey(provider), key.trim()).apply()
    }
}
