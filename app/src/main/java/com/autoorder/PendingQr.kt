package com.autoorder

object PendingQr {
    @Volatile
    var dataUrl: String? = null

    @Volatile
    var label: String? = null

    fun clear() {
        dataUrl = null
        label = null
    }
}
