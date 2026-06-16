# autoOrder — Mô tả chức năng

Tài liệu mô tả tổng quan các chức năng hiện có của app Android `com.autoorder`. App ban đầu chỉ là công cụ đọc tin nhắn 1-1 trên Zalo Web qua WebView (xem [CLAUDE.md](../CLAUDE.md)), nay đã mở rộng thành một mini-POS quản lý đơn hàng cho shop bán hàng qua Zalo: bắt tin → AI bóc tách đơn → chốt đơn → in QR chuyển khoản → thống kê doanh thu.

> Package: `com.autoorder` · Kotlin · minSdk 24 · targetSdk 34

---

## 1. Bản đồ màn hình

App có 6 Activity (xem [AndroidManifest.xml](../app/src/main/AndroidManifest.xml)) + 1 foreground service:

| Activity | Vai trò |
|---|---|
| [ChatWebActivity](../app/src/main/java/com/autoorder/ChatWebActivity.kt) | Launcher. WebView host `chat.zalo.me`, JS observer bắt tin mới, popup chốt đơn. |
| [OrdersActivity](../app/src/main/java/com/autoorder/OrdersActivity.kt) | Dashboard đơn hàng: list, thống kê, sản phẩm bán chạy, khách hàng. |
| [ProductsActivity](../app/src/main/java/com/autoorder/ProductsActivity.kt) | Quản lý menu/sản phẩm (CRUD, sắp xếp, active/inactive). |
| [ZaloChatsActivity](../app/src/main/java/com/autoorder/ZaloChatsActivity.kt) | Danh sách chat Zalo đã nhận diện, gắn tên/nhãn riêng. |
| [BankAccountsActivity](../app/src/main/java/com/autoorder/BankAccountsActivity.kt) | Quản lý tài khoản nhận tiền dùng để sinh QR VietQR. |
| [MessagesActivity](../app/src/main/java/com/autoorder/MessagesActivity.kt) | List tin nhắn thô đã capture từ Zalo (debug / lịch sử). |
| [SettingsActivity](../app/src/main/java/com/autoorder/SettingsActivity.kt) | Cài đặt: AI provider/key, auto-sync, view mode, export DB, các shortcut. |
| [WebMonitorService](../app/src/main/java/com/autoorder/WebMonitorService.kt) | Foreground service `dataSync` giữ WebView sống để nhận tin khi app ở background. |

---

## 2. Bắt tin Zalo (Zalo Web bridge)

Cốt lõi vẫn như mô tả trong [CLAUDE.md](../CLAUDE.md), nhưng đã tách phần JS lớn ra thành module riêng:

- [ZaloObserverJs.kt](../app/src/main/java/com/autoorder/ZaloObserverJs.kt) — tập trung toàn bộ script inject (MutationObserver + classifier 1-1 / group / My Documents).
- [ZaloTimeParser.kt](../app/src/main/java/com/autoorder/ZaloTimeParser.kt) — parse "Vài giây", "2 giờ", "Hôm qua", "12:34"… về epoch ms để sắp xếp.
- [NewMsgNotifier.kt](../app/src/main/java/com/autoorder/NewMsgNotifier.kt) — emit notification khi có tin mới (kênh `POST_NOTIFICATIONS`).
- [MessagesDb.kt](../app/src/main/java/com/autoorder/MessagesDb.kt) — vẫn dùng SQLite riêng (`autoorder.db`) cho tin nhắn thô, schema y nguyên CLAUDE.md.

Bridge JS ↔ Kotlin: `AutoOrderBridge.onNewMessage(animId, name, preview, timeText)` và `onDump(...)` cho nút Quét.

### WebMonitorService

[WebMonitorService.kt](../app/src/main/java/com/autoorder/WebMonitorService.kt) là foreground service kiểu `dataSync` (khai báo trong manifest). Nó **không** host WebView riêng; thay vào đó giữ một notification "đang theo dõi" và đóng vai trò keep-alive khi user để app ở background. WebView vẫn nằm trong `ChatWebActivity`.

---

## 3. AI bóc đơn hàng

[OrderExtractor.kt](../app/src/main/java/com/autoorder/OrderExtractor.kt) là module chuyển một đoạn chat (hoặc nhiều tin gộp) thành object đơn hàng có cấu trúc.

Hỗ trợ **3 AI provider** chọn được trong Settings:

| Provider | Endpoint |
|---|---|
| Anthropic Claude | `https://api.anthropic.com/v1/messages` |
| OpenRouter | `https://openrouter.ai/api/v1/chat/completions` |
| OpenAI | `https://api.openai.com/v1/chat/completions` |

Key đọc theo thứ tự: user nhập trong Settings → fallback `BuildConfig.*_API_KEY` (gradle).

Pipeline:

1. Build prompt với danh sách sản phẩm hiện hành (lấy từ `ShopDb.listProducts`) để model match món vào product có sẵn.
2. Gọi API → nhận JSON: `phone`, `address`, `items[]` (`product_id` nếu match được, `product_name`, `quantity`, `note`, `raw_text`).
3. Hiển thị dialog xem trước cho user (`dialog_order.xml` / `dialog_order_detail.xml`) để chỉnh tay trước khi lưu.
4. Lưu xuống `ShopDb` qua `insertOrder(...)`.

Raw JSON model trả về được giữ trong cột `orders.raw_json` để debug / re-parse sau này.

---

## 4. Quản lý đơn hàng (OrdersActivity)

Dashboard 3 tab (`ORDERS`, `PRODUCTS`, `CUSTOMERS`) với bộ lọc thời gian + trạng thái thanh toán.

### Lọc thời gian
- `TODAY`, `YESTERDAY`, `D7` (7 ngày), `D30` (30 ngày), `ALL`, `CUSTOM` (date picker `from..to`).
- Múi giờ cố định `Asia/Ho_Chi_Minh` cho `order_date` (`YYYY-MM-DD`).

### Lọc trạng thái
- `ALL` / `UNPAID` / `PAID` — toggle qua chip ở header.

### Tab ORDERS
- List đơn từ mới → cũ, mỗi item hiển thị `items_text` đã pre-build, tổng tiền, peer Zalo, trạng thái paid.
- Tap item → mở `dialog_order_detail.xml`: xem chi tiết, sửa note, toggle paid, copy SĐT/địa chỉ, hiện **QR VietQR** chuyển khoản (xem §6), xoá đơn.

### Tab PRODUCTS (Sản phẩm bán chạy)
- [ProductsSoldAdapter.kt](../app/src/main/java/com/autoorder/ProductsSoldAdapter.kt) hiển thị tổng `quantity` và `revenue` theo product, sort desc.
- [BarChartView.kt](../app/src/main/java/com/autoorder/BarChartView.kt) custom view vẽ bar chart đơn theo ngày trong khoảng đã lọc.

### Tab CUSTOMERS
- Group đơn theo key: `phone` → `sender_name` → `conv_name` → `(không tên)`.
- Hiển thị số đơn, tổng doanh thu, tap → xem các đơn của khách đó.

### Statistics header
- `statOrders`, `statRevenue`, `statItems` — tổng số đơn, doanh thu, tổng món trong khoảng lọc.

---

## 5. Sản phẩm / Menu (ProductsActivity)

[ProductsActivity.kt](../app/src/main/java/com/autoorder/ProductsActivity.kt):

- CRUD sản phẩm qua `dialog_product_edit.xml` (category, name, price, note, active, sort_order).
- Sắp xếp theo `(category, sort_order, name)`.
- `active = false` ẩn khỏi UI chốt đơn nhưng giữ trong DB để đơn cũ vẫn link được `product_id`.
- Snapshot `product_name` + `unit_price` được copy vào `order_items` lúc chốt đơn → đổi giá menu không ảnh hưởng đơn cũ.

---

## 6. Tài khoản nhận tiền + VietQR (BankAccountsActivity)

[BankAccount.kt](../app/src/main/java/com/autoorder/BankAccount.kt) + [BankQr.kt](../app/src/main/java/com/autoorder/BankQr.kt):

- Lưu nhiều tài khoản (`bank_bin`, `account_no`, `account_name`, `is_default`).
- [BankQr.kt](../app/src/main/java/com/autoorder/BankQr.kt) build URL VietQR (img.vietqr.io) với amount + memo (mặc định = `order_code` dạng `<zaloId>_<yyyymmdd>_<totalK>k` — xem `OrderRecord.makeCode`).
- Khi mở chi tiết đơn → tap "QR chuyển khoản" → load ảnh qua [Coil](https://coil-kt.github.io/coil/) (`coil.load(...)`).
- [PendingQr.kt](../app/src/main/java/com/autoorder/PendingQr.kt) giữ state QR đang chờ user xác nhận đã thu tiền.

`dialog_checkout.xml` cho phép chốt đơn nhanh ngay từ `ChatWebActivity` (Settings có shortcut "Checkout" mở thẳng dialog này).

---

## 7. Danh sách chat Zalo (ZaloChatsActivity)

[ZaloChat.kt](../app/src/main/java/com/autoorder/ZaloChat.kt) + [ZaloChatsAdapter.kt](../app/src/main/java/com/autoorder/ZaloChatsAdapter.kt):

- Bảng riêng `zalo_chats` trong `shop.db` (xem [ShopDb.kt](../app/src/main/java/com/autoorder/ShopDb.kt)).
- Auto-insert khi observer thấy `animId` mới; user có thể đặt nhãn / tên ngắn qua `dialog_zalo_chat_edit.xml`.
- `zalo_id` này được dùng làm tiền tố cho `order_code` để đối soát chuyển khoản.

---

## 8. Settings (SettingsActivity)

Các nhóm chính:

| Nhóm | Mục |
|---|---|
| View | View mode (Light/Dark/System) — `dialog_view_mode.xml`. |
| Catalogue | Sản phẩm, Tài khoản nhận tiền, Zalo chats. |
| Auto-sync | Bật/tắt foreground service + cấu hình ngưỡng. |
| AI | Chọn provider (Claude / OpenRouter / OpenAI), nhập API key, hiển thị summary key đang dùng. |
| Data | Export DB ra `Downloads/shop_<yyyyMMdd_HHmmss>.db` (Scoped Storage trên Q+, `WRITE_EXTERNAL_STORAGE` ≤ P). |
| Shortcuts | Mở Inbox đơn, mở Checkout, quay lại Chat. |

[AppPrefs.kt](../app/src/main/java/com/autoorder/AppPrefs.kt) là wrapper `SharedPreferences` cho tất cả setting (AI provider/key, view mode, auto-sync flags…).

---

## 9. Database (`shop.db`)

Định nghĩa đầy đủ trong [ShopDb.kt](../app/src/main/java/com/autoorder/ShopDb.kt). Hiện tại `DB_VERSION = 5`, gồm 4 bảng:

| Bảng | Mục đích |
|---|---|
| `products` | Menu shop. |
| `orders` | Header đơn + `order_code` UNIQUE (`<zaloId>_<yyyymmdd>_<totalK>k`). |
| `order_items` | Chi tiết từng dòng món (snapshot price + quantity). |
| `zalo_chats` | Map `animId` ↔ tên/nhãn user đặt. |

Index quan trọng: `orders(order_date)`, `orders(phone)`, `orders(zalo_id)`, `UNIQUE order_code`.

Tin nhắn thô vẫn ở DB tách rời `autoorder.db` ([MessagesDb.kt](../app/src/main/java/com/autoorder/MessagesDb.kt)).

Path:
- `/data/data/com.autoorder/databases/shop.db`
- `/data/data/com.autoorder/databases/autoorder.db`

---

## 10. Đồng bộ server (chưa wire)

[docs/API_SPEC.md](API_SPEC.md) đặc tả REST API server cần expose để app chuyển từ lưu local sang lưu cloud (multi-tenant theo `shop_id`, idempotency theo `client_order_id`). Phần này còn ở dạng spec, chưa có HTTP client trong code.

---

## 11. Permissions

| Permission | Mục đích |
|---|---|
| `INTERNET` | WebView Zalo + gọi AI provider + VietQR image. |
| `POST_NOTIFICATIONS` | Notify tin mới + notification của foreground service (Android 13+). |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | [WebMonitorService](../app/src/main/java/com/autoorder/WebMonitorService.kt). |
| `WRITE_EXTERNAL_STORAGE` (maxSdk 28) | Export DB ra Downloads trên Android ≤ 9. Android 10+ dùng MediaStore. |

---

## 12. Luồng end-to-end của 1 đơn

1. Khách nhắn vào Zalo → `ChatWebActivity` WebView nhận → observer JS bắn `onNewMessage` → lưu vào `messages` ([MessagesDb](../app/src/main/java/com/autoorder/MessagesDb.kt)) → push notification ([NewMsgNotifier](../app/src/main/java/com/autoorder/NewMsgNotifier.kt)).
2. User mở conv trong app, bấm **Chốt đơn** → mở `dialog_checkout.xml`.
3. App gom các tin gần nhất của peer → [OrderExtractor](../app/src/main/java/com/autoorder/OrderExtractor.kt) gọi AI → trả JSON đơn.
4. User review/sửa → `ShopDb.insertOrder(...)` ghi `orders` + `order_items`, sinh `order_code` duy nhất.
5. Mở chi tiết đơn → bấm QR → [BankQr](../app/src/main/java/com/autoorder/BankQr.kt) sinh URL VietQR với amount + `order_code` làm memo → hiển thị cho khách quét.
6. Khi nhận được tiền → user toggle `paid = true`. [OrdersActivity](../app/src/main/java/com/autoorder/OrdersActivity.kt) cập nhật doanh thu / chart.
