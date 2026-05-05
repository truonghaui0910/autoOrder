# autoOrder

App Android đọc tin nhắn 1-1 mới đến trên Zalo Web (`chat.zalo.me`) qua WebView, lưu SQLite. Mục tiêu cuối: tự động trích xuất đơn hàng (món, SĐT, địa chỉ) từ chat.

## Kỹ thuật

- Kotlin, AGP 8.5.2, Kotlin 1.9.24, Java 17.
- `minSdk 24`, `targetSdk 34`, `compileSdk 34`.
- Package: `com.autoorder`.
- Deps chính: `androidx.appcompat`, `material 1.12.0`, `recyclerview`. Không dùng Room — dùng `SQLiteOpenHelper` thuần.

## Kiến trúc

App có **2 Activity**, không có service/overlay (đã bỏ phần bong bóng nổi):

- **`ChatWebActivity`** (launcher): nhúng `chat.zalo.me` trong WebView desktop UA + `setInitialScale(25)` + viewport meta `width=1400` để Zalo render layout 3 cột đầy đủ. Inject JS observer bắt tin mới.
- **`MessagesActivity`**: danh sách tin từ DB, sắp xếp `captured_at DESC`.

Cả 2 dùng chung `bottom_bar.xml` qua `<include>`. Bar có 5 mục: Chat / Tải lại / Quét / Tin (counter) / DB.

## Cách bắt tin mới (JS injection)

Inject vào WebView sau `onPageFinished`:

1. **Phân loại conv-item** (sidebar) bằng attribute, KHÔNG dựa vào avatar:
   - `data-id="div_TabMsg_ThrdChFileXFER"` → My Documents (skip)
   - `anim-data-id` mở đầu `g` → group (skip)
   - `anim-data-id` rỗng → other (skip)
   - Còn lại → 1-1 (xử lý)

2. **Phát hiện chưa đọc** trên 1-1:
   - `.z-conv-message.--unread` xuất hiện
   - hoặc `.conv-action__unread-v2` xuất hiện
   - hoặc `.z-noti-badge.--counter`

3. **MutationObserver** subtree + childList + attributes (filter `class`). Mỗi mutation walk lên `.msg-item` gần nhất, nếu là 1-1 + unread → trích `(animId, name, preview, timeText)` qua `AutoOrderBridge.onNewMessage(...)`.

4. **Dedup** ở JS theo `animId → preview` cuối + ở Kotlin theo `animId|content` trong cửa sổ 5s.

5. **Trích xuất nội dung:**
   - Tên: `.conv-item-title__name .truncate` (fallback `.conv-item-title__name`).
   - Preview: `.z-conv-message__preview-message`.
   - Time: `.preview-time` (vd "Vài giây", "2 giờ").
   - Nếu preview có prefix `"Tên: ..."` → tách ra; nếu là `"Bạn:"` → là tin mình gửi → bỏ.

## Giới hạn cố hữu

- Tin của hội thoại **không mở** chỉ có **preview** (Zalo không render nội dung đầy đủ trừ khi conv được active). Tin dài bị `…`.
- Conv-item ngoài viewport sidebar không được render (virtual scroll). Khi có tin mới, Zalo move conv lên top → vào viewport → bắt được.
- Toàn bộ chỉ hoạt động khi `ChatWebActivity` đang sống. Đóng → WebView destroy → ngừng nhận. Muốn chạy nền liên tục cần host WebView trong foreground service (chưa làm).
- Group bị bỏ hoàn toàn (theo yêu cầu user).
- Đăng nhập Zalo trong WebView là phiên riêng, không share cookie với Chrome.

## DB schema (SQLite, version 2, drop & recreate khi upgrade)

`messages` table:

| col           | type    | ghi chú                                              |
|---------------|---------|------------------------------------------------------|
| id            | INT PK  | autoinc                                              |
| captured_at   | INT     | epoch ms khi lưu                                     |
| kind          | TEXT    | `incoming` / `message` / `preview` / dump categories |
| conv_name     | TEXT    | tên hội thoại (cho preview = chính tên peer)         |
| sender_name   | TEXT    | tên người gửi (đã parse từ "Tên: ..." nếu có)        |
| content       | TEXT    | nội dung đã strip prefix tên                         |
| time_text     | TEXT    | chuỗi thời gian Zalo hiển thị (vd "Vài giây")        |
| is_self       | INT     | 1 nếu mình gửi                                       |
| css_class     | TEXT    | debug                                                |
| content_hash  | TEXT    | hash để dedup                                        |

Index: `captured_at`, `content_hash`, `kind`.

DB path: `/data/data/com.autoorder/databases/autoorder.db`.

## Files quan trọng

- [ChatWebActivity.kt](app/src/main/java/com/autoorder/ChatWebActivity.kt) — WebView host + JS observer + JsBridge (`onNewMessage`, `onMessage`, `onDump`).
- [MessagesActivity.kt](app/src/main/java/com/autoorder/MessagesActivity.kt) — list view DB, refresh khi `onResume`.
- [MessagesAdapter.kt](app/src/main/java/com/autoorder/MessagesAdapter.kt) — RecyclerView adapter.
- [MessagesDb.kt](app/src/main/java/com/autoorder/MessagesDb.kt) — `SQLiteOpenHelper` + `insert()` + `queryAll()`.
- [MessageRow.kt](app/src/main/java/com/autoorder/MessageRow.kt) — data class.
- [bottom_bar.xml](app/src/main/res/layout/bottom_bar.xml) — bar dùng chung 2 activity.

## Đã quan sát từ DOM thật của Zalo Web

- `.msg-item` = mỗi mục trong sidebar (KHÔNG phải `.conv-item` — `.conv-item` là class con bên trong).
- 1-1: avatar có class `zavatar-single`. Group: `zavatar-multi` + có `<i class="fa fa-Community_16_Filled">` icon trong title.
- Cả 1-1 và group đều có class `grd-ava` trên avatar wrapper → KHÔNG dùng class này để phân biệt. Phải dùng `anim-data-id` prefix.
- Tin của mình gửi (preview) có prefix `"Bạn:"`.

## Kế hoạch tiếp (chưa làm)

1. Mở rộng capture sang **bubble tin nhắn đầy đủ** khi user mở 1 hội thoại (DOM `.chat-item` đã quan sát được; cần wire lại trong observer).
2. Auto-poll: tự click conv chưa đọc, capture full content, click trở lại — để bắt tin của mọi conv không chỉ active.
3. **Extractor đơn hàng**: từ `content` → `(món, sđt, địa chỉ)`. Bắt đầu bằng regex; nâng cấp lên gọi Claude API.
4. Foreground service host WebView để chạy nền 24/7.
5. Notification khi có tin mới đến.

## Testing

1. Build trong Android Studio (auto sync Gradle wrapper). Hoặc CLI: `./gradlew assembleDebug`.
2. Cài lên máy Android (Wireless Debugging hoặc USB).
3. Mở app → đăng nhập Zalo trong WebView (cookie persist).
4. Mở Logcat filter tag `AutoOrder`:
   - `NEW from='...' (anim=...) :: ...` = tin mới đã bắt và lưu.
   - `DUMP <conv-1on1#N> :: ...` = output nút Quét.
5. Bấm **DB** trên bottom bar → xem danh sách tin lưu DESC theo `captured_at`.

## Lệnh adb hữu ích

```powershell
# Xem DB
adb shell "run-as com.autoorder sqlite3 /data/data/com.autoorder/databases/autoorder.db 'SELECT id, datetime(captured_at/1000,\"unixepoch\",\"+7 hours\") t, sender_name, substr(content,1,80) FROM messages ORDER BY id DESC LIMIT 30;'"

# Wireless ADB connect lần đầu (sau khi cắm USB)
adb tcpip 5555
adb connect <IP_PHONE>:5555
```
