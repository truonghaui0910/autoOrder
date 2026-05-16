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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Giai đoạn dev: drop & recreate cho đơn giản
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        db.execSQL("DROP TABLE IF EXISTS zalo_chats")
        onCreate(db)
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
