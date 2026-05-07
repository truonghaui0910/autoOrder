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
    val paid: Boolean = false
)

class ShopDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "shop.db"
        const val DB_VERSION = 2
        const val T_PROD = "products"
        const val T_ORD = "orders"
        const val T_OI = "order_items"
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
                paid INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_ord_date ON $T_ORD(order_date)")
        db.execSQL("CREATE INDEX idx_ord_phone ON $T_ORD(phone)")

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

        seedProducts(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $T_ORD ADD COLUMN paid INTEGER NOT NULL DEFAULT 0")
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

    fun queryOrders(fromDate: String?, toDate: String?, paid: Boolean? = null, limit: Int = 500): List<OrderRecord> {
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
        val sql = StringBuilder(
            "SELECT id, created_at, order_date, conv_name, sender_name, phone, address, " +
                "items_text, raw_json, total_amount, note, paid FROM $T_ORD"
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
                        paid = c.getInt(11) == 1
                    )
                )
            }
        }
        return out
    }

    fun summary(fromDate: String?, toDate: String?, paid: Boolean? = null): OrderSummary {
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
            "SELECT COUNT(DISTINCT o.id), COALESCE(SUM(o.total_amount), 0), " +
                "COALESCE((SELECT SUM(oi.quantity) FROM $T_OI oi " +
                "JOIN $T_ORD o2 ON o2.id = oi.order_id"
        )
        if (where.isNotEmpty()) {
            sql.append(" WHERE ").append(
                where.toString()
                    .replace("o.order_date", "o2.order_date")
                    .replace("o.paid", "o2.paid")
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

    fun ordersPerDay(fromDate: String?, toDate: String?, paid: Boolean? = null): List<Pair<String, Int>> {
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
                "COUNT(*) AS oc, COALESCE(SUM(total_amount),0) AS rev " +
                "FROM $T_ORD"
        )
        if (where.isNotEmpty()) sql.append(" WHERE ").append(where)
        sql.append(" GROUP BY gkey ORDER BY rev DESC, oc DESC LIMIT ").append(limit)

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
