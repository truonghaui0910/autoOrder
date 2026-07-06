# autoOrder

App Android đọc tin nhắn 1-1 mới đến trên Zalo Web (`chat.zalo.me`) qua WebView, lưu SQLite. Mục tiêu cuối: tự động trích xuất đơn hàng (món, SĐT, địa chỉ) từ chat.

## Kỹ thuật

- Kotlin, AGP 8.5.2, Kotlin 1.9.24, Java 17.
- `minSdk 24`, `targetSdk 34`, `compileSdk 34`.
- Package: `com.autoorder`.
- Deps chính: `androidx.appcompat`, `material 1.12.0`, `recyclerview`. Không dùng Room — dùng `SQLiteOpenHelper` thuần.

## Kiến trúc

Xem [docs/FEATURES.md](docs/FEATURES.md) cho bản đồ Activity đầy đủ. Bản mô tả này tập trung phần capture Zalo:

- **`ChatWebActivity`** (launcher): nhúng `chat.zalo.me` trong WebView desktop UA + `setInitialScale(25)` + viewport meta `width=1400` để Zalo render layout 3 cột đầy đủ. Inject JS observer bắt tin mới, đẩy vào pipeline chốt đơn / notifier — **không lưu tin nhắn thô xuống DB nữa** (bảng `messages` cũ đã bỏ).

Bottom bar dùng chung qua `bottom_bar.xml` (`<include>`).

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

## DB

Toàn bộ dữ liệu nghiệp vụ nằm ở `shop.db` — xem [ShopDb.kt](app/src/main/java/com/autoorder/ShopDb.kt) và bảng ở [docs/FEATURES.md §9](docs/FEATURES.md). Path: `/data/data/com.autoorder/databases/shop.db`.

File `autoorder.db` của bản cũ đã bỏ; [ChatWebActivity](app/src/main/java/com/autoorder/ChatWebActivity.kt) gọi `deleteDatabase("autoorder.db")` trong `onCreate` để dọn khi user update APK.

## Files quan trọng

- [ChatWebActivity.kt](app/src/main/java/com/autoorder/ChatWebActivity.kt) — WebView host + JS observer + JsBridge (`onNewMessage`, `onMessage`, `onDump`).
- [ShopDb.kt](app/src/main/java/com/autoorder/ShopDb.kt) — `SQLiteOpenHelper` cho `shop.db` (products / orders / order_items / zalo_chats).
- [bottom_bar.xml](app/src/main/res/layout/bottom_bar.xml) — bar dùng chung các activity.

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
adb shell "run-as com.autoorder sqlite3 /data/data/com.autoorder/databases/shop.db 'SELECT id, order_code, order_date, total, paid FROM orders ORDER BY id DESC LIMIT 30;'"

# Wireless ADB connect lần đầu (sau khi cắm USB)
adb tcpip 5555
adb connect <IP_PHONE>:5555
```
