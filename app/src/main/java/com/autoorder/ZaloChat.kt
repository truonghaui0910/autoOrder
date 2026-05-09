package com.autoorder

data class ZaloChat(
    val id: Long,
    val zaloId: String,
    val name: String,
    val avatarUrl: String,
    val isGroup: Boolean,
    val chatType: String,
    val address: String,
    val phone: String,
    val createdAt: Long,
    val status: String,
    val lastMsgAt: Long = 0L,
    val lastMsgText: String = ""
) {
    fun phoneList(): List<String> = splitMulti(phone)
    fun addressList(): List<String> = splitMulti(address)

    companion object {
        fun splitMulti(s: String): List<String> =
            s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
