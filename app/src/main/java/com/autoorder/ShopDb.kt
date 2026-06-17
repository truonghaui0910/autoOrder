package com.autoorder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Product(
    val id: Long = 0,
    val category: String,
    val name: String,
    val price: Int,
    val note: String = "",
    val active: Boolean = true,
    val sortOrder: Int = 0
)

data class OrderItem(
    val productId: Long?,
    val productName: String,
    val quantity: Double,
    val unitPrice: Int,
    val note: String = "",
    val rawText: String = ""
) {
    val lineTotal: Long get() = Math.round(quantity * unitPrice)
}

data class DuplicateOrderInfo(
    val id: Long,
    val createdAt: Long,
    val senderName: String,
    val convName: String,
    val phone: String,
    val itemsText: String,
    val totalAmount: Long,
    val paid: Boolean
)

data class OrderRecord(
    val id: Long = 0,
    val createdAt: Long,
    val orderDate: String,
    val convName: String,
    val senderName: String,
    val phone: String,
    val address: String,
    val itemsText: String,
    val rawJson: String,
    val totalAmount: Long,
    val note: String = "",
    val paid: Boolean = false,
    val zaloId: String = "",
    val orderCode: String = ""
) {
    companion object {
        /** zaloid_yyyymmdd_totalK, ví dụ 250.000 → 250k. orderDate dạng yyyy-MM-dd. */
        fun makeCode(zaloId: String, orderDate: String, totalAmount: Long): String {
            val zid = zaloId.ifBlank { "noid" }
            val ymd = orderDate.replace("-", "")
            val totalK = ((totalAmount + 500) / 1000)
            return "${zid}_${ymd}_${totalK}k"
        }
    }
}

class ShopDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "shop.db"
        const val DB_VERSION = 5
        const val T_PROD = "products"
        const val T_ORD = "orders"
        const val T_OI = "order_items"
        const val T_CHATS = "zalo_chats"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_PROD (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT NOT NULL,
                name TEXT NOT NULL,
                price INTEGER NOT NULL,
                note TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                sort_order INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_prod_active ON $T_PROD(active)")
        db.execSQL("CREATE INDEX idx_prod_cat ON $T_PROD(category)")

        db.execSQL(
            """
            CREATE TABLE $T_ORD (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                order_date TEXT NOT NULL,
                conv_name TEXT,
                sender_name TEXT,
                phone TEXT,
                address TEXT,
                items_text TEXT,
                raw_json TEXT,
                total_amount INTEGER NOT NULL DEFAULT 0,
                note TEXT,
                paid INTEGER NOT NULL DEFAULT 0,
                zalo_id TEXT,
                order_code TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ord_date ON $T_ORD(order_date)")
        db.execSQL("CREATE INDEX idx_ord_phone ON $T_ORD(phone)")
        db.execSQL("CREATE INDEX idx_ord_zaloid ON $T_ORD(zalo_id)")
        db.execSQL("CREATE UNIQUE INDEX idx_ord_code ON $T_ORD(order_code) WHERE order_code IS NOT NULL")

        db.execSQL(
            """
            CREATE TABLE $T_OI (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                order_id INTEGER NOT NULL,
                product_id INTEGER,
                product_name TEXT NOT NULL,
                quantity REAL NOT NULL,
                unit_price INTEGER NOT NULL,
                line_total INTEGER NOT NULL,
                note TEXT,
                raw_text TEXT,
                FOREIGN KEY(order_id) REFERENCES $T_ORD(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_oi_order ON $T_OI(order_id)")
        db.execSQL("CREATE INDEX idx_oi_product ON $T_OI(product_id)")

        createChatsTable(db)

        seedProducts(db)
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

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $T_ORD ADD COLUMN paid INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $T_ORD ADD COLUMN zalo_id TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ord_zaloid ON $T_ORD(zalo_id)")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $T_ORD ADD COLUMN order_code TEXT")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_ord_code ON $T_ORD(order_code) WHERE order_code IS NOT NULL")
        }
        if (oldVersion < 5) {
            createChatsTable(db)
            // Copy dữ liệu cũ từ autoorder.db nếu có
            runCatching {
                val shopPath = db.path
                val srcPath = shopPath.replace("shop.db", "autoorder.db")
                if (java.io.File(srcPath).exists()) {
                    db.execSQL("ATTACH DATABASE '$srcPath' AS srcdb")
                    val hasTable = db.rawQuery(
                        "SELECT name FROM srcdb.sqlite_master WHERE type='table' AND name='$T_CHATS'",
                        null
                    ).use { it.moveToFirst() }
                    if (hasTable) {
                        db.execSQL(
                            "INSERT OR IGNORE INTO $T_CHATS " +
                                "(zalo_id, name, avatar_url, is_group, chat_type, address, phone, created_at, last_msg_at, last_msg_text, status) " +
                                "SELECT zalo_id, name, avatar_url, is_group, chat_type, address, phone, created_at, last_msg_at, last_msg_text, status " +
                                "FROM srcdb.$T_CHATS"
                        )
                    }
                    db.execSQL("DETACH DATABASE srcdb")
                }
            }
        }
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

    fun getFirstOrderGroupChat(): ZaloChat? {
        readableDatabase.rawQuery(
            "SELECT id, zalo_id, name, avatar_url, is_group, chat_type, address, phone, created_at, status, last_msg_at, last_msg_text " +
                "FROM $T_CHATS WHERE chat_type = 'order' AND status = 'active' " +
                "ORDER BY last_msg_at DESC, id DESC LIMIT 1",
            null
        ).use { c ->
            if (c.moveToNext()) {
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

    fun getZaloChatsByName(name: String): List<ZaloChat> {
        if (name.isBlank()) return emptyList()
        val out = ArrayList<ZaloChat>()
        readableDatabase.rawQuery(
            "SELECT id, zalo_id, name, avatar_url, is_group, chat_type, address, phone, created_at, status, last_msg_at, last_msg_text " +
                "FROM $T_CHATS WHERE LOWER(TRIM(name)) = LOWER(TRIM(?)) AND status = 'active' " +
                "ORDER BY last_msg_at DESC, id DESC",
            arrayOf(name)
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

    fun getZaloChatsByPhone(phone: String): List<ZaloChat> {
        val pn = phone.replace(Regex("\\D"), "")
        if (pn.isBlank()) return emptyList()
        val out = ArrayList<ZaloChat>()
        readableDatabase.rawQuery(
            "SELECT id, zalo_id, name, avatar_url, is_group, chat_type, address, phone, created_at, status, last_msg_at, last_msg_text " +
                "FROM $T_CHATS WHERE status = 'active' AND phone IS NOT NULL AND phone <> '' " +
                "ORDER BY last_msg_at DESC, id DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val rawPhones = c.getString(7) ?: ""
                val hit = ZaloChat.splitMulti(rawPhones)
                    .map { it.replace(Regex("\\D"), "") }
                    .filter { it.isNotBlank() }
                    .any { p -> p.contains(pn) || pn.contains(p) }
                if (!hit) continue
                out.add(
                    ZaloChat(
                        id = c.getLong(0),
                        zaloId = c.getString(1) ?: "",
                        name = c.getString(2) ?: "",
                        avatarUrl = c.getString(3) ?: "",
                        isGroup = c.getInt(4) == 1,
                        chatType = c.getString(5) ?: "normal",
                        address = c.getString(6) ?: "",
                        phone = rawPhones,
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

    fun markAsCustomer(zaloId: String, phone: String, address: String): Boolean {
        if (zaloId.isBlank()) return false
        val db = writableDatabase
        db.rawQuery(
            "SELECT chat_type, phone, address FROM $T_CHATS WHERE zalo_id = ?",
            arrayOf(zaloId)
        ).use { c ->
            if (!c.moveToFirst()) return false
            val curType = c.getString(0) ?: "normal"
            val curPhone = c.getString(1) ?: ""
            val curAddr = c.getString(2) ?: ""
            val cv = ContentValues()
            if (curType == "normal") cv.put("chat_type", "customer")
            if (curPhone.isBlank() && phone.isNotBlank()) cv.put("phone", phone.trim())
            if (curAddr.isBlank() && address.isNotBlank()) cv.put("address", address.trim())
            if (cv.size() == 0) return false
            db.update(T_CHATS, cv, "zalo_id = ?", arrayOf(zaloId))
            return true
        }
    }

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

    fun getNamesByChatType(chatType: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT name FROM $T_CHATS WHERE chat_type = ? AND status = 'active' AND name IS NOT NULL AND name <> ''",
            arrayOf(chatType)
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) ?: "")
        }
        return out.filter { it.isNotBlank() }
    }

    fun countZaloChats(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $T_CHATS", null).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun seedProducts(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        data class S(val cat: String, val name: String, val price: Int, val note: String)
        val seed = listOf(
            S("TRÀ", "Nhiệt đới", 30000, ""),
            S("TRÀ", "Dưa lưới", 35000, ""),
            S("TRÀ", "Dâu tằm", 35000, ""),
            S("TRÀ", "Atiso chua ngọt", 35000, ""),
            S("TRÀ", "Đác cam chanh", 40000, ""),
            S("TRÀ", "Mãng cầu", 40000, ""),
            S("TRÀ", "Mãng cầu mix dâu tằm", 40000, ""),
            S("TRÀ", "Hũ đác dâu tằm", 10000, ""),
            S("ĂN VẶT", "Chân gà sốt thái S", 60000, "6-7 chân"),
            S("ĂN VẶT", "Chân gà sốt thái M", 90000, "9-10 chân"),
            S("ĂN VẶT", "Chân gà sốt thái L", 130000, "13-14 chân"),
            S("ĂN VẶT", "Combo trải nghiệm chân trứng", 60000, "4-5 chân + 100g trứng"),
            S("ĂN VẶT", "Trứng non sốt thái", 50000, "250g"),
            S("ĂN VẶT", "Gà ủ muối 1/2", 150000, ""),
            S("ĂN VẶT", "Gà ủ muối 1 con", 280000, "")
        )
        seed.forEachIndexed { idx, s ->
            val cv = ContentValues().apply {
                put("category", s.cat)
                put("name", s.name)
                put("price", s.price)
                put("note", s.note)
                put("active", 1)
                put("sort_order", idx)
                put("created_at", now)
                put("updated_at", now)
            }
            db.insert(T_PROD, null, cv)
        }
    }

    fun listProducts(activeOnly: Boolean = false): List<Product> {
        val sql = StringBuilder(
            "SELECT id, category, name, price, note, active, sort_order FROM $T_PROD"
        )
        if (activeOnly) sql.append(" WHERE active = 1")
        sql.append(" ORDER BY category, sort_order, id")
        val out = ArrayList<Product>()
        readableDatabase.rawQuery(sql.toString(), null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Product(
                        id = c.getLong(0),
                        category = c.getString(1) ?: "",
                        name = c.getString(2) ?: "",
                        price = c.getInt(3),
                        note = c.getString(4) ?: "",
                        active = c.getInt(5) == 1,
                        sortOrder = c.getInt(6)
                    )
                )
            }
        }
        return out
    }

    fun getProduct(id: Long): Product? {
        readableDatabase.rawQuery(
            "SELECT id, category, name, price, note, active, sort_order FROM $T_PROD WHERE id = ?",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToFirst()) {
                return Product(
                    id = c.getLong(0),
                    category = c.getString(1) ?: "",
                    name = c.getString(2) ?: "",
                    price = c.getInt(3),
                    note = c.getString(4) ?: "",
                    active = c.getInt(5) == 1,
                    sortOrder = c.getInt(6)
                )
            }
        }
        return null
    }

    fun upsertProduct(p: Product): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("category", p.category)
            put("name", p.name)
            put("price", p.price)
            put("note", p.note)
            put("active", if (p.active) 1 else 0)
            put("sort_order", p.sortOrder)
            put("updated_at", now)
        }
        return if (p.id > 0) {
            writableDatabase.update(T_PROD, cv, "id = ?", arrayOf(p.id.toString()))
            p.id
        } else {
            cv.put("created_at", now)
            writableDatabase.insert(T_PROD, null, cv)
        }
    }

    fun deleteProduct(id: Long) {
        writableDatabase.delete(T_PROD, "id = ?", arrayOf(id.toString()))
    }

    data class OrderSummary(
        val orderCount: Int,
        val totalRevenue: Long,
        val totalItems: Double
    )

    data class ProductSold(
        val productId: Long?,
        val productName: String,
        val totalQty: Double,
        val totalRevenue: Long
    )

    data class CustomerStat(
        val displayName: String,
        val phone: String,
        val orderCount: Int,
        val totalRevenue: Long
    )

    fun queryOrders(
        fromDate: String?,
        toDate: String?,
        paid: Boolean? = null,
        search: String? = null,
        limit: Int = 500
    ): List<OrderRecord> {
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (fromDate != null) { where.append("order_date >= ?"); args.add(fromDate) }
        if (toDate != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("order_date <= ?"); args.add(toDate)
        }
        if (paid != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("paid = ?"); args.add(if (paid) "1" else "0")
        }
        val q = search?.trim().orEmpty()
        if (q.isNotEmpty()) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("(LOWER(sender_name) LIKE ? OR LOWER(conv_name) LIKE ? OR LOWER(phone) LIKE ? OR LOWER(address) LIKE ? OR LOWER(COALESCE(zalo_id,'')) LIKE ? OR LOWER(COALESCE(items_text,'')) LIKE ?)")
            val like = "%" + q.lowercase() + "%"
            repeat(6) { args.add(like) }
        }
        val sql = StringBuilder(
            "SELECT id, created_at, order_date, conv_name, sender_name, phone, address, " +
                "items_text, raw_json, total_amount, note, paid, COALESCE(zalo_id,'') FROM $T_ORD"
        )
        if (where.isNotEmpty()) sql.append(" WHERE ").append(where)
        sql.append(" ORDER BY created_at DESC LIMIT ").append(limit)

        val out = ArrayList<OrderRecord>()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    OrderRecord(
                        id = c.getLong(0),
                        createdAt = c.getLong(1),
                        orderDate = c.getString(2) ?: "",
                        convName = c.getString(3) ?: "",
                        senderName = c.getString(4) ?: "",
                        phone = c.getString(5) ?: "",
                        address = c.getString(6) ?: "",
                        itemsText = c.getString(7) ?: "",
                        rawJson = c.getString(8) ?: "",
                        totalAmount = c.getLong(9),
                        note = c.getString(10) ?: "",
                        paid = c.getInt(11) == 1,
                        zaloId = c.getString(12) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun summary(
        fromDate: String?,
        toDate: String?,
        paid: Boolean? = null,
        search: String? = null
    ): OrderSummary {
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (fromDate != null) { where.append("o.order_date >= ?"); args.add(fromDate) }
        if (toDate != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("o.order_date <= ?"); args.add(toDate)
        }
        if (paid != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("o.paid = ?"); args.add(if (paid) "1" else "0")
        }
        val q = search?.trim().orEmpty()
        if (q.isNotEmpty()) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("(LOWER(o.sender_name) LIKE ? OR LOWER(o.conv_name) LIKE ? OR LOWER(o.phone) LIKE ? OR LOWER(o.address) LIKE ? OR LOWER(COALESCE(o.zalo_id,'')) LIKE ? OR LOWER(COALESCE(o.items_text,'')) LIKE ?)")
            val like = "%" + q.lowercase() + "%"
            repeat(6) { args.add(like) }
        }
        val sql = StringBuilder(
            "SELECT COUNT(DISTINCT o.id), COALESCE(SUM(o.total_amount), 0), " +
                "COALESCE((SELECT SUM(oi.quantity) FROM $T_OI oi " +
                "JOIN $T_ORD o2 ON o2.id = oi.order_id"
        )
        if (where.isNotEmpty()) {
            sql.append(" WHERE ").append(
                where.toString()
                    .replace("o.order_date", "o2.order_date")
                    .replace("o.paid", "o2.paid")
                    .replace("o.sender_name", "o2.sender_name")
                    .replace("o.conv_name", "o2.conv_name")
                    .replace("o.phone", "o2.phone")
                    .replace("o.address", "o2.address")
                    .replace("o.zalo_id", "o2.zalo_id")
                    .replace("o.items_text", "o2.items_text")
            )
        }
        sql.append("), 0) FROM $T_ORD o")
        if (where.isNotEmpty()) sql.append(" WHERE ").append(where)

        val allArgs = (args + args).toTypedArray()
        readableDatabase.rawQuery(sql.toString(), allArgs).use { c ->
            if (c.moveToFirst()) {
                return OrderSummary(c.getInt(0), c.getLong(1), c.getDouble(2))
            }
        }
        return OrderSummary(0, 0, 0.0)
    }

    fun productsSold(fromDate: String?, toDate: String?, paid: Boolean? = null, limit: Int = 50): List<ProductSold> {
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (fromDate != null) { where.append("o.order_date >= ?"); args.add(fromDate) }
        if (toDate != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("o.order_date <= ?"); args.add(toDate)
        }
        if (paid != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("o.paid = ?"); args.add(if (paid) "1" else "0")
        }
        val sql = StringBuilder(
            "SELECT oi.product_id, oi.product_name, SUM(oi.quantity), SUM(oi.line_total) " +
                "FROM $T_OI oi JOIN $T_ORD o ON o.id = oi.order_id"
        )
        if (where.isNotEmpty()) sql.append(" WHERE ").append(where)
        sql.append(" GROUP BY oi.product_id, oi.product_name ORDER BY SUM(oi.line_total) DESC LIMIT ")
            .append(limit)

        val out = ArrayList<ProductSold>()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ProductSold(
                        productId = if (c.isNull(0)) null else c.getLong(0),
                        productName = c.getString(1) ?: "",
                        totalQty = c.getDouble(2),
                        totalRevenue = c.getLong(3)
                    )
                )
            }
        }
        return out
    }

    fun revenueByCategory(
        fromDate: String?,
        toDate: String?,
        paid: Boolean? = null,
        search: String? = null
    ): List<Pair<String, Long>> {
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (fromDate != null) { where.append("o.order_date >= ?"); args.add(fromDate) }
        if (toDate != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("o.order_date <= ?"); args.add(toDate)
        }
        if (paid != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("o.paid = ?"); args.add(if (paid) "1" else "0")
        }
        val q = search?.trim().orEmpty()
        if (q.isNotEmpty()) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("(LOWER(o.sender_name) LIKE ? OR LOWER(o.conv_name) LIKE ? OR LOWER(o.phone) LIKE ? OR LOWER(o.address) LIKE ? OR LOWER(COALESCE(o.zalo_id,'')) LIKE ? OR LOWER(COALESCE(o.items_text,'')) LIKE ?)")
            val like = "%" + q.lowercase() + "%"
            repeat(6) { args.add(like) }
        }
        val sql = StringBuilder(
            "SELECT COALESCE(NULLIF(TRIM(p.category),''), 'Khác') AS cat, " +
                "COALESCE(SUM(oi.line_total), 0) AS rev " +
                "FROM $T_OI oi JOIN $T_ORD o ON o.id = oi.order_id " +
                "LEFT JOIN $T_PROD p ON p.id = oi.product_id"
        )
        if (where.isNotEmpty()) sql.append(" WHERE ").append(where)
        sql.append(" GROUP BY cat HAVING rev > 0 ORDER BY rev DESC")

        val out = ArrayList<Pair<String, Long>>()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add((c.getString(0) ?: "Khác") to c.getLong(1))
            }
        }
        return out
    }

    fun ordersPerDay(
        fromDate: String?,
        toDate: String?,
        paid: Boolean? = null,
        search: String? = null
    ): List<Pair<String, Int>> {
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (fromDate != null) { where.append("order_date >= ?"); args.add(fromDate) }
        if (toDate != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("order_date <= ?"); args.add(toDate)
        }
        if (paid != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("paid = ?"); args.add(if (paid) "1" else "0")
        }
        val q = search?.trim().orEmpty()
        if (q.isNotEmpty()) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("(LOWER(sender_name) LIKE ? OR LOWER(conv_name) LIKE ? OR LOWER(phone) LIKE ? OR LOWER(address) LIKE ? OR LOWER(COALESCE(zalo_id,'')) LIKE ? OR LOWER(COALESCE(items_text,'')) LIKE ?)")
            val like = "%" + q.lowercase() + "%"
            repeat(6) { args.add(like) }
        }
        val sql = StringBuilder("SELECT order_date, COUNT(*) FROM $T_ORD")
        if (where.isNotEmpty()) sql.append(" WHERE ").append(where)
        sql.append(" GROUP BY order_date ORDER BY order_date ASC")

        val out = ArrayList<Pair<String, Int>>()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add((c.getString(0) ?: "") to c.getInt(1))
            }
        }
        return out
    }

    fun customersStat(fromDate: String?, toDate: String?, paid: Boolean? = null, limit: Int = 200): List<CustomerStat> {
        val where = StringBuilder()
        val args = ArrayList<String>()
        if (fromDate != null) { where.append("order_date >= ?"); args.add(fromDate) }
        if (toDate != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("order_date <= ?"); args.add(toDate)
        }
        if (paid != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("paid = ?"); args.add(if (paid) "1" else "0")
        }
        val groupKey = "COALESCE(NULLIF(TRIM(phone),''), NULLIF(TRIM(sender_name),''), NULLIF(TRIM(conv_name),''), '(không tên)')"
        val sql = StringBuilder(
            "SELECT $groupKey AS gkey, " +
                "MAX(CASE WHEN TRIM(COALESCE(sender_name,''))<>'' THEN sender_name " +
                "WHEN TRIM(COALESCE(conv_name,''))<>'' THEN conv_name ELSE '' END) AS name, " +
                "MAX(COALESCE(phone,'')) AS ph, " +
                "COUNT(*) AS oc, COALESCE(SUM(total_amount),0) AS rev, " +
                "MAX(created_at) AS last_at " +
                "FROM $T_ORD"
        )
        if (where.isNotEmpty()) sql.append(" WHERE ").append(where)
        sql.append(" GROUP BY gkey ORDER BY oc DESC, last_at DESC LIMIT ").append(limit)

        val out = ArrayList<CustomerStat>()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val gkey = c.getString(0) ?: ""
                val name = c.getString(1) ?: ""
                val phone = c.getString(2) ?: ""
                val display = name.ifBlank { gkey }
                out.add(
                    CustomerStat(
                        displayName = display.ifBlank { "(không tên)" },
                        phone = phone,
                        orderCount = c.getInt(3),
                        totalRevenue = c.getLong(4)
                    )
                )
            }
        }
        return out
    }

    fun queryOrderItems(orderId: Long): List<OrderItem> {
        val out = ArrayList<OrderItem>()
        readableDatabase.rawQuery(
            "SELECT product_id, product_name, quantity, unit_price, note, raw_text " +
                "FROM $T_OI WHERE order_id = ? ORDER BY id",
            arrayOf(orderId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    OrderItem(
                        productId = if (c.isNull(0)) null else c.getLong(0),
                        productName = c.getString(1) ?: "",
                        quantity = c.getDouble(2),
                        unitPrice = c.getInt(3),
                        note = c.getString(4) ?: "",
                        rawText = c.getString(5) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun setOrderPaid(orderId: Long, paid: Boolean) {
        val cv = ContentValues().apply { put("paid", if (paid) 1 else 0) }
        writableDatabase.update(T_ORD, cv, "id = ?", arrayOf(orderId.toString()))
    }

    fun updateOrder(orderId: Long, order: OrderRecord, items: List<OrderItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("order_date", order.orderDate)
                put("conv_name", order.convName)
                put("sender_name", order.senderName)
                put("phone", order.phone)
                put("address", order.address)
                put("items_text", order.itemsText)
                put("total_amount", order.totalAmount)
                put("note", order.note)
                put("paid", if (order.paid) 1 else 0)
                put("zalo_id", order.zaloId)
                if (order.orderCode.isNotBlank()) put("order_code", order.orderCode)
            }
            db.update(T_ORD, cv, "id = ?", arrayOf(orderId.toString()))
            db.delete(T_OI, "order_id = ?", arrayOf(orderId.toString()))
            items.forEach { oi ->
                val ic = ContentValues().apply {
                    put("order_id", orderId)
                    if (oi.productId != null) put("product_id", oi.productId) else putNull("product_id")
                    put("product_name", oi.productName)
                    put("quantity", oi.quantity)
                    put("unit_price", oi.unitPrice)
                    put("line_total", oi.lineTotal)
                    put("note", oi.note)
                    put("raw_text", oi.rawText)
                }
                db.insert(T_OI, null, ic)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteOrder(orderId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(T_OI, "order_id = ?", arrayOf(orderId.toString()))
            db.delete(T_ORD, "id = ?", arrayOf(orderId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun ordersCountToday(today: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_ORD WHERE order_date = ?",
            arrayOf(today)
        ).use { c -> if (c.moveToFirst()) return c.getInt(0) }
        return 0
    }

    fun getOrderIdByCode(orderCode: String): Long {
        if (orderCode.isBlank()) return -1L
        readableDatabase.rawQuery(
            "SELECT id FROM $T_ORD WHERE order_code = ? LIMIT 1",
            arrayOf(orderCode)
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return -1L
    }

    fun getOrderPaidByCode(orderCode: String): Pair<Long, Boolean>? {
        if (orderCode.isBlank()) return null
        readableDatabase.rawQuery(
            "SELECT id, paid FROM $T_ORD WHERE order_code = ? LIMIT 1",
            arrayOf(orderCode)
        ).use { c -> if (c.moveToFirst()) return c.getLong(0) to (c.getInt(1) == 1) }
        return null
    }

    /**
     * Tìm các đơn cùng SĐT trong cùng ngày (theo order_date `yyyy-MM-dd`).
     * Phone được normalize bằng TRIM. Bỏ qua nếu phone blank.
     */
    fun findOrdersByPhoneOnDate(phone: String, orderDate: String): List<DuplicateOrderInfo> {
        val p = phone.trim()
        if (p.isBlank() || orderDate.isBlank()) return emptyList()
        val out = mutableListOf<DuplicateOrderInfo>()
        readableDatabase.rawQuery(
            "SELECT id, created_at, sender_name, conv_name, phone, items_text, total_amount, paid " +
                "FROM $T_ORD WHERE TRIM(phone) = ? AND order_date = ? " +
                "ORDER BY created_at DESC",
            arrayOf(p, orderDate)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DuplicateOrderInfo(
                        id = c.getLong(0),
                        createdAt = c.getLong(1),
                        senderName = c.getString(2) ?: "",
                        convName = c.getString(3) ?: "",
                        phone = c.getString(4) ?: "",
                        itemsText = c.getString(5) ?: "",
                        totalAmount = c.getLong(6),
                        paid = c.getInt(7) == 1
                    )
                )
            }
        }
        return out
    }

    /**
     * Trả về (id, isNew). Nếu order_code đã tồn tại → trả lại id cũ, isNew=false (không insert lại).
     */
    fun insertOrderWithDedup(order: OrderRecord, items: List<OrderItem>): Pair<Long, Boolean> {
        if (order.orderCode.isNotBlank()) {
            val existing = getOrderIdByCode(order.orderCode)
            if (existing > 0) return existing to false
        }
        return insertOrder(order, items) to true
    }

    fun insertOrder(order: OrderRecord, items: List<OrderItem>): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put("created_at", order.createdAt)
                put("order_date", order.orderDate)
                put("conv_name", order.convName)
                put("sender_name", order.senderName)
                put("phone", order.phone)
                put("address", order.address)
                put("items_text", order.itemsText)
                put("raw_json", order.rawJson)
                put("total_amount", order.totalAmount)
                put("note", order.note)
                put("paid", if (order.paid) 1 else 0)
                put("zalo_id", order.zaloId)
                if (order.orderCode.isNotBlank()) put("order_code", order.orderCode)
            }
            val orderId = db.insert(T_ORD, null, cv)
            items.forEach { oi ->
                val ic = ContentValues().apply {
                    put("order_id", orderId)
                    if (oi.productId != null) put("product_id", oi.productId) else putNull("product_id")
                    put("product_name", oi.productName)
                    put("quantity", oi.quantity)
                    put("unit_price", oi.unitPrice)
                    put("line_total", oi.lineTotal)
                    put("note", oi.note)
                    put("raw_text", oi.rawText)
                }
                db.insert(T_OI, null, ic)
            }
            db.setTransactionSuccessful()
            return orderId
        } finally {
            db.endTransaction()
        }
    }
}
