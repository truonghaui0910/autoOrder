package com.autoorder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MessagesDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "autoorder.db"
        const val DB_VERSION = 5
        const val TABLE = "messages"
        const val T_CHATS = "zalo_chats"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at INTEGER NOT NULL,
                kind TEXT NOT NULL,
                conv_name TEXT,
                sender_name TEXT,
                content TEXT NOT NULL,
                time_text TEXT,
                is_self INTEGER NOT NULL DEFAULT 0,
                css_class TEXT,
                content_hash TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_msg_time ON $TABLE(captured_at)")
        db.execSQL("CREATE INDEX idx_msg_hash ON $TABLE(content_hash)")
        db.execSQL("CREATE INDEX idx_msg_kind ON $TABLE(kind)")

        createChatsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Giai đoạn dev: drop & recreate cho đơn giản
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        db.execSQL("DROP TABLE IF EXISTS $T_CHATS")
        onCreate(db)
    }

    private fun createChatsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_CHATS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                zalo_id TEXT NOT NULL UNIQUE,
                name TEXT,
                avatar_url TEXT,
                is_group INTEGER NOT NULL DEFAULT 0,
                chat_type TEXT NOT NULL DEFAULT 'normal',
                address TEXT,
                phone TEXT,
                created_at INTEGER NOT NULL,
                last_msg_at INTEGER NOT NULL DEFAULT 0,
                last_msg_text TEXT,
                status TEXT NOT NULL DEFAULT 'active'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_chats_zalo ON $T_CHATS(zalo_id)")
        db.execSQL("CREATE INDEX idx_chats_status ON $T_CHATS(status)")
        db.execSQL("CREATE INDEX idx_chats_type ON $T_CHATS(chat_type)")
        db.execSQL("CREATE INDEX idx_chats_last ON $T_CHATS(last_msg_at)")
    }

    fun upsertZaloChat(
        zaloId: String,
        name: String,
        avatarUrl: String,
        isGroup: Boolean,
        lastMsgAt: Long,
        lastMsgText: String
    ) {
        if (zaloId.isBlank()) return
        val db = writableDatabase
        db.rawQuery(
            "SELECT id, name, avatar_url FROM $T_CHATS WHERE zalo_id = ?",
            arrayOf(zaloId)
        ).use { c ->
            if (c.moveToFirst()) {
                val existingId = c.getLong(0)
                val existingName = c.getString(1) ?: ""
                val existingAvatar = c.getString(2) ?: ""
                val cv = ContentValues()
                if (name.isNotBlank() && name != existingName) cv.put("name", name)
                if (avatarUrl.isNotBlank() && avatarUrl != existingAvatar) cv.put("avatar_url", avatarUrl)
                if (lastMsgAt > 0) {
                    cv.put("last_msg_at", lastMsgAt)
                    cv.put("last_msg_text", lastMsgText)
                }
                if (cv.size() > 0) {
                    db.update(T_CHATS, cv, "id = ?", arrayOf(existingId.toString()))
                }
                return
            }
        }
        val cv = ContentValues().apply {
            put("zalo_id", zaloId)
            put("name", name)
            put("avatar_url", avatarUrl)
            put("is_group", if (isGroup) 1 else 0)
            put("chat_type", "normal")
            put("created_at", System.currentTimeMillis())
            put("last_msg_at", lastMsgAt)
            put("last_msg_text", lastMsgText)
            put("status", "active")
        }
        db.insert(T_CHATS, null, cv)
    }

    fun listZaloChats(): List<ZaloChat> {
        val out = ArrayList<ZaloChat>()
        readableDatabase.rawQuery(
            "SELECT id, zalo_id, name, avatar_url, is_group, chat_type, address, phone, created_at, status, last_msg_at, last_msg_text " +
                "FROM $T_CHATS ORDER BY last_msg_at DESC, created_at DESC, id DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ZaloChat(
                        id = c.getLong(0),
                        zaloId = c.getString(1) ?: "",
                        name = c.getString(2) ?: "",
                        avatarUrl = c.getString(3) ?: "",
                        isGroup = c.getInt(4) == 1,
                        chatType = c.getString(5) ?: "normal",
                        address = c.getString(6) ?: "",
                        phone = c.getString(7) ?: "",
                        createdAt = c.getLong(8),
                        status = c.getString(9) ?: "active",
                        lastMsgAt = c.getLong(10),
                        lastMsgText = c.getString(11) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun updateZaloChat(
        id: Long,
        name: String,
        chatType: String,
        status: String,
        phone: String,
        address: String
    ) {
        val cv = ContentValues().apply {
            put("name", name)
            put("chat_type", chatType)
            put("status", status)
            put("phone", phone)
            put("address", address)
        }
        writableDatabase.update(T_CHATS, cv, "id = ?", arrayOf(id.toString()))
    }

    fun getZaloChatByName(name: String): ZaloChat? {
        if (name.isBlank()) return null
        readableDatabase.rawQuery(
            "SELECT id, zalo_id, name, avatar_url, is_group, chat_type, address, phone, created_at, status, last_msg_at, last_msg_text " +
                "FROM $T_CHATS WHERE LOWER(TRIM(name)) = LOWER(TRIM(?)) AND status = 'active' " +
                "ORDER BY last_msg_at DESC, id DESC LIMIT 1",
            arrayOf(name)
        ).use { c ->
            if (c.moveToFirst()) {
                return ZaloChat(
                    id = c.getLong(0),
                    zaloId = c.getString(1) ?: "",
                    name = c.getString(2) ?: "",
                    avatarUrl = c.getString(3) ?: "",
                    isGroup = c.getInt(4) == 1,
                    chatType = c.getString(5) ?: "normal",
                    address = c.getString(6) ?: "",
                    phone = c.getString(7) ?: "",
                    createdAt = c.getLong(8),
                    status = c.getString(9) ?: "active",
                    lastMsgAt = c.getLong(10),
                    lastMsgText = c.getString(11) ?: ""
                )
            }
        }
        return null
    }

    fun getZaloChatByZaloId(zaloId: String): ZaloChat? {
        if (zaloId.isBlank()) return null
        readableDatabase.rawQuery(
            "SELECT id, zalo_id, name, avatar_url, is_group, chat_type, address, phone, created_at, status, last_msg_at, last_msg_text " +
                "FROM $T_CHATS WHERE zalo_id = ?",
            arrayOf(zaloId)
        ).use { c ->
            if (c.moveToFirst()) {
                return ZaloChat(
                    id = c.getLong(0),
                    zaloId = c.getString(1) ?: "",
                    name = c.getString(2) ?: "",
                    avatarUrl = c.getString(3) ?: "",
                    isGroup = c.getInt(4) == 1,
                    chatType = c.getString(5) ?: "normal",
                    address = c.getString(6) ?: "",
                    phone = c.getString(7) ?: "",
                    createdAt = c.getLong(8),
                    status = c.getString(9) ?: "active",
                    lastMsgAt = c.getLong(10),
                    lastMsgText = c.getString(11) ?: ""
                )
            }
        }
        return null
    }

    private fun appendMulti(zaloId: String, column: String, value: String): Boolean {
        val v = value.trim()
        if (zaloId.isBlank() || v.isEmpty()) return false
        val db = writableDatabase
        db.rawQuery("SELECT $column FROM $T_CHATS WHERE zalo_id = ?", arrayOf(zaloId)).use { c ->
            if (!c.moveToFirst()) return false
            val cur = c.getString(0) ?: ""
            val list = ZaloChat.splitMulti(cur)
            if (list.any { it.equals(v, ignoreCase = true) }) return false
            val merged = (list + v).joinToString("\n")
            val cv = ContentValues().apply { put(column, merged) }
            db.update(T_CHATS, cv, "zalo_id = ?", arrayOf(zaloId))
            return true
        }
    }

    fun appendPhoneToZaloChat(zaloId: String, phone: String): Boolean =
        appendMulti(zaloId, "phone", phone)

    fun appendAddressToZaloChat(zaloId: String, address: String): Boolean =
        appendMulti(zaloId, "address", address)

    fun setChatTypeByZaloId(
        zaloId: String,
        chatType: String,
        fallbackName: String = "",
        fallbackAvatarUrl: String = "",
        fallbackIsGroup: Boolean = false
    ) {
        if (zaloId.isBlank()) return
        val db = writableDatabase
        val cv = ContentValues().apply { put("chat_type", chatType) }
        val rows = db.update(T_CHATS, cv, "zalo_id = ?", arrayOf(zaloId))
        if (rows > 0) return
        val ins = ContentValues().apply {
            put("zalo_id", zaloId)
            put("name", fallbackName)
            put("avatar_url", fallbackAvatarUrl)
            put("is_group", if (fallbackIsGroup) 1 else 0)
            put("chat_type", chatType)
            put("created_at", System.currentTimeMillis())
            put("last_msg_at", 0L)
            put("last_msg_text", "")
            put("status", "active")
        }
        db.insert(T_CHATS, null, ins)
    }

    fun deleteZaloChat(id: Long) {
        writableDatabase.delete(T_CHATS, "id = ?", arrayOf(id.toString()))
    }

    fun countZaloChats(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $T_CHATS", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun insert(
        kind: String,
        convName: String,
        senderName: String,
        content: String,
        timeText: String,
        isSelf: Boolean,
        cssClass: String
    ): Long {
        val cv = ContentValues().apply {
            put("captured_at", System.currentTimeMillis())
            put("kind", kind)
            put("conv_name", convName)
            put("sender_name", senderName)
            put("content", content)
            put("time_text", timeText)
            put("is_self", if (isSelf) 1 else 0)
            put("css_class", cssClass)
            put("content_hash", "${convName.hashCode()}_${senderName.hashCode()}_${content.hashCode()}_${timeText.hashCode()}")
        }
        return writableDatabase.insert(TABLE, null, cv)
    }

    fun count(): Long {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            return if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    fun queryAll(limit: Int = 1000): List<MessageRow> {
        val out = ArrayList<MessageRow>()
        readableDatabase.rawQuery(
            "SELECT id, captured_at, kind, conv_name, sender_name, content, time_text, is_self " +
                "FROM $TABLE ORDER BY captured_at DESC, id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    MessageRow(
                        id = c.getLong(0),
                        capturedAt = c.getLong(1),
                        kind = c.getString(2) ?: "",
                        convName = c.getString(3) ?: "",
                        senderName = c.getString(4) ?: "",
                        content = c.getString(5) ?: "",
                        timeText = c.getString(6) ?: "",
                        isSelf = c.getInt(7) == 1
                    )
                )
            }
        }
        return out
    }
}
