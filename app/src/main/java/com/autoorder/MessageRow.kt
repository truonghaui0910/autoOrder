package com.autoorder

data class MessageRow(
    val id: Long,
    val capturedAt: Long,
    val kind: String,
    val convName: String,
    val senderName: String,
    val content: String,
    val timeText: String,
    val isSelf: Boolean
)
