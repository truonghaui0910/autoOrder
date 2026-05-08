# autoOrder Server API Spec

Tài liệu mô tả schema database & REST API mà server cần expose để app Android (`com.autoorder`) có thể chuyển từ lưu local SQLite sang lưu lên server.

Mục tiêu chuyển đổi: thay thế chức năng `ShopDb.insertOrder(...)`, `queryOrders(...)`, `listProducts(...)`, ... trong app bằng HTTP API.

---

## 1. Tổng quan

- Base URL: `https://<host>/api/v1`
- Encoding: `application/json; charset=utf-8`
- Auth: `Authorization: Bearer <token>` (token cấp cho từng shop)
- Mọi timestamp: epoch milliseconds (UTC). Khi cần ngày local, dùng `order_date` định dạng `YYYY-MM-DD` theo timezone `Asia/Ho_Chi_Minh`.
- Tiền tệ: VND, lưu là số nguyên (không có phần thập phân). `quantity` là số thực (cho phép 0.5 con).

### Multi-tenant

Mỗi shop có 1 `shop_id` (server tự suy ra từ token, client KHÔNG gửi). Mọi resource bên dưới đều scope theo shop của token.

### Định dạng lỗi

```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "phone is required",
    "details": { "field": "phone" }
  }
}
```

HTTP status:
- `400` validation
- `401` thiếu/sai token
- `403` token hợp lệ nhưng không có quyền
- `404` không tìm thấy resource
- `409` conflict (vd `client_order_id` trùng)
- `500` server error

---

## 2. Database schema (gợi ý)

Bám sát SQLite hiện tại trong app ([ShopDb.kt](../app/src/main/java/com/autoorder/ShopDb.kt)) + thêm các cột phục vụ multi-tenant & idempotency.

### `shops`

| col          | type         | ghi chú                            |
|--------------|--------------|------------------------------------|
| id           | BIGSERIAL PK |                                    |
| name         | TEXT         |                                    |
| api_token    | TEXT UNIQUE  | dùng cho `Authorization: Bearer`   |
| created_at   | BIGINT       | epoch ms                           |

### `products`

| col         | type         | ghi chú                                         |
|-------------|--------------|-------------------------------------------------|
| id          | BIGSERIAL PK |                                                 |
| shop_id     | BIGINT FK    | → shops.id, index                               |
| category    | TEXT NOT NULL| "TRÀ", "ĂN VẶT", ...                            |
| name        | TEXT NOT NULL|                                                 |
| price       | INT NOT NULL | VND, integer                                    |
| note        | TEXT         | mô tả phụ ("6-7 chân", "250g")                  |
| active      | BOOL DEFAULT TRUE |                                            |
| sort_order  | INT DEFAULT 0|                                                 |
| created_at  | BIGINT       | epoch ms                                        |
| updated_at  | BIGINT       | epoch ms                                        |

Index: `(shop_id, active)`, `(shop_id, category)`.

### `orders`

| col              | type         | ghi chú                                              |
|------------------|--------------|------------------------------------------------------|
| id               | BIGSERIAL PK | server-side id                                       |
| shop_id          | BIGINT FK    | index                                                |
| client_order_id  | TEXT         | UUID do app sinh khi save → idempotency             |
| created_at       | BIGINT       | epoch ms (client cung cấp; nếu thiếu, server set)   |
| order_date       | TEXT         | `YYYY-MM-DD` (Asia/Ho_Chi_Minh), index              |
| conv_name        | TEXT         | tên hội thoại Zalo (peer name)                       |
| sender_name      | TEXT         |                                                      |
| phone            | TEXT         | index                                                |
| address          | TEXT         |                                                      |
| items_text       | TEXT         | preview text đã build sẵn ở client                  |
| raw_json         | TEXT         | JSON gốc do Claude parse — debug/audit              |
| total_amount     | BIGINT NOT NULL DEFAULT 0 | đơn vị VND                              |
| note             | TEXT         |                                                      |
| paid             | BOOL DEFAULT FALSE |                                                |
| updated_at       | BIGINT       | epoch ms                                             |

Index: `(shop_id, order_date)`, `(shop_id, phone)`, `UNIQUE(shop_id, client_order_id)` khi `client_order_id` không null.

### `order_items`

| col          | type         | ghi chú                                       |
|--------------|--------------|-----------------------------------------------|
| id           | BIGSERIAL PK |                                               |
| order_id     | BIGINT FK    | → orders.id ON DELETE CASCADE, index          |
| product_id   | BIGINT       | FK → products.id (nullable cho off-menu)     |
| product_name | TEXT NOT NULL| snapshot tại thời điểm chốt đơn               |
| quantity     | NUMERIC(10,3) NOT NULL |                                     |
| unit_price   | INT NOT NULL | snapshot giá tại lúc chốt                     |
| line_total   | BIGINT NOT NULL | = round(quantity * unit_price)             |
| note         | TEXT         | "ít đường", "ít đá", ...                      |
| raw_text     | TEXT         | text gốc khách viết (nếu có)                  |

---

## 3. Endpoints

### 3.1 Auth check (sanity)

```
GET /api/v1/me
```

Response 200:
```json
{ "shop_id": 12, "shop_name": "Shop A" }
```

---

### 3.2 Products

#### `GET /api/v1/products`

Query params:
- `active_only` (bool, default `false`)

Response 200:
```json
{
  "products": [
    {
      "id": 1,
      "category": "TRÀ",
      "name": "Mãng cầu",
      "price": 40000,
      "note": "",
      "active": true,
      "sort_order": 5
    }
  ]
}
```

#### `POST /api/v1/products`

Body:
```json
{
  "category": "TRÀ",
  "name": "Mãng cầu",
  "price": 40000,
  "note": "",
  "active": true,
  "sort_order": 5
}
```

Response 201: object `Product` (same shape as item trong list).

#### `PUT /api/v1/products/{id}`

Body: như `POST` (partial cũng được — server merge).
Response 200: object `Product`.

#### `DELETE /api/v1/products/{id}`

Response 204.

---

### 3.3 Orders

#### `POST /api/v1/orders`

Tạo đơn mới. Đây là endpoint quan trọng nhất — thay thế `ShopDb.insertOrder(...)`.

Request body:
```json
{
  "client_order_id": "8f9b1d3c-1a2e-4c11-9b22-7e0f5d2b1a01",
  "created_at": 1715140800000,
  "order_date": "2026-05-08",
  "conv_name": "Khách Nguyễn Văn A",
  "sender_name": "Nguyễn Văn A",
  "phone": "0901234567",
  "address": "123 Lê Lợi, Q1, HCM",
  "items_text": "1 x Mãng cầu — 40.000₫\n2 x Chân gà sốt thái M — 180.000₫\n\nTổng: 220.000₫",
  "raw_json": "{...}",
  "total_amount": 220000,
  "note": "",
  "paid": false,
  "items": [
    {
      "product_id": 6,
      "product_name": "Mãng cầu",
      "quantity": 1.0,
      "unit_price": 40000,
      "line_total": 40000,
      "note": "ít đường",
      "raw_text": ""
    },
    {
      "product_id": 10,
      "product_name": "Chân gà sốt thái M",
      "quantity": 2.0,
      "unit_price": 90000,
      "line_total": 180000,
      "note": "",
      "raw_text": ""
    }
  ]
}
```

Ghi chú:
- `client_order_id`: nên là UUID v4 do app sinh. Nếu app retry với cùng `client_order_id` → server trả lại đúng order cũ (idempotent), HTTP `200` thay vì `201`.
- `product_id`: `null` cho item off-menu.
- `line_total`: server có thể tự tính lại để verify; nếu lệch → trả `400`.
- `created_at`, `order_date` do client gửi để giữ đúng thời điểm chốt theo giờ máy của shop.

Response 201 (hoặc 200 nếu idempotent hit):
```json
{
  "id": 4521,
  "client_order_id": "8f9b1d3c-...",
  "created_at": 1715140800000,
  "order_date": "2026-05-08",
  "conv_name": "...",
  "sender_name": "...",
  "phone": "...",
  "address": "...",
  "items_text": "...",
  "raw_json": "{...}",
  "total_amount": 220000,
  "note": "",
  "paid": false,
  "updated_at": 1715140800000,
  "items": [ /* same shape as input */ ]
}
```

#### `GET /api/v1/orders`

Query params (tất cả optional):
- `from_date` (`YYYY-MM-DD`)
- `to_date`   (`YYYY-MM-DD`)
- `paid`      (`true|false`)
- `phone`     (lọc theo SĐT)
- `limit`     (default `100`, max `500`)
- `cursor`    (opaque, để paginate; server trả `next_cursor` trong response)

Response 200:
```json
{
  "orders": [ { /* Order, không gồm items */ } ],
  "next_cursor": "eyJpZCI6IDQ1MDB9"
}
```

> Lý do không trả `items` trong list: list view trong app chỉ hiển thị `items_text` đã pre-build. Khi user mở chi tiết → gọi `GET /orders/{id}`.

#### `GET /api/v1/orders/{id}`

Response 200: object `Order` đầy đủ kèm `items`.

#### `PATCH /api/v1/orders/{id}`

Update các field nhỏ. Body chỉ chứa field cần đổi:
```json
{ "paid": true }
```
hoặc
```json
{ "note": "Đã giao 18h" }
```

Response 200: object `Order` đầy đủ.

#### `DELETE /api/v1/orders/{id}`

Xóa hẳn (cascade `order_items`). Response 204.

---

### 3.4 Reports / Analytics

Phục vụ màn `OrdersActivity` (summary, products sold, customers stat, orders per day).

#### `GET /api/v1/reports/summary`

Query: `from_date`, `to_date`, `paid` (optional).

Response 200:
```json
{
  "order_count": 42,
  "total_revenue": 5_400_000,
  "total_items": 87.5
}
```

#### `GET /api/v1/reports/products-sold`

Query: `from_date`, `to_date`, `paid`, `limit` (default 50).

Response 200:
```json
{
  "items": [
    {
      "product_id": 10,
      "product_name": "Chân gà sốt thái M",
      "total_qty": 23.0,
      "total_revenue": 2_070_000
    }
  ]
}
```

#### `GET /api/v1/reports/orders-per-day`

Query: `from_date`, `to_date`, `paid`.

Response 200:
```json
{
  "days": [
    { "order_date": "2026-05-01", "order_count": 7 },
    { "order_date": "2026-05-02", "order_count": 12 }
  ]
}
```

#### `GET /api/v1/reports/customers`

Query: `from_date`, `to_date`, `paid`, `limit` (default 200).

Response 200:
```json
{
  "customers": [
    {
      "display_name": "Nguyễn Văn A",
      "phone": "0901234567",
      "order_count": 5,
      "total_revenue": 1_100_000
    }
  ]
}
```

Group key (giống SQL hiện tại):
```
COALESCE(NULLIF(TRIM(phone),''), NULLIF(TRIM(sender_name),''), NULLIF(TRIM(conv_name),''), '(không tên)')
```

---

## 4. Mapping Kotlin ↔ JSON

| Kotlin field (OrderRecord)      | JSON field        |
|---------------------------------|-------------------|
| `id: Long`                      | `id`              |
| `createdAt: Long`               | `created_at`      |
| `orderDate: String`             | `order_date`      |
| `convName: String`              | `conv_name`       |
| `senderName: String`            | `sender_name`     |
| `phone: String`                 | `phone`           |
| `address: String`               | `address`         |
| `itemsText: String`             | `items_text`      |
| `rawJson: String`               | `raw_json`        |
| `totalAmount: Long`             | `total_amount`    |
| `note: String`                  | `note`            |
| `paid: Boolean`                 | `paid`            |

| Kotlin field (OrderItem)        | JSON field        |
|---------------------------------|-------------------|
| `productId: Long?`              | `product_id`      |
| `productName: String`           | `product_name`    |
| `quantity: Double`              | `quantity`        |
| `unitPrice: Int`                | `unit_price`      |
| `lineTotal: Long` (computed)    | `line_total`      |
| `note: String`                  | `note`            |
| `rawText: String`               | `raw_text`        |

---

## 5. Idempotency & offline (quan trọng)

App có thể save đơn khi mạng yếu → cần retry an toàn:

1. App sinh `client_order_id = UUID.randomUUID().toString()` ngay khi user bấm **Lưu**.
2. App lưu local 1 bản copy ở SQLite với cờ `synced = 0`.
3. Background worker gọi `POST /orders` với `client_order_id`.
4. Server enforce `UNIQUE(shop_id, client_order_id)`. Khi gặp trùng → trả `200` + order hiện có.
5. App nhận `id` từ server → set `synced = 1`, lưu `server_id`.

> Khuyến nghị: KHÔNG bỏ hẳn SQLite local — giữ làm queue offline + cache. Server là source of truth.

---

## 6. Câu hỏi cần xác nhận trước khi implement server

- Bạn muốn server tự tính `total_amount` và `line_total`, hay tin client? (gợi ý: server tính lại để chống tampering)
- `client_order_id` có cần unique global hay chỉ unique theo shop? (gợi ý: theo shop là đủ)
- Auth dùng static token per shop, hay cần login user/password + JWT? (giai đoạn đầu nên static token cho đơn giản)
- Có cần endpoint sync products 2 chiều (client edit → server) hay chỉ 1 chiều server → client?
- Có cần webhook / push notification từ server xuống app khi đơn được mark `paid` từ web admin không?
