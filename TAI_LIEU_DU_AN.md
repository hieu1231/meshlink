# MeshLink – Tài liệu dự án và tiến độ

Cập nhật: 16/10/2026

MeshLink là app Android (Kotlin, giao diện XML) nhắn tin, đồng bộ dữ liệu và gọi điện **không cần Internet**. Các điện thoại tự nối với nhau qua Bluetooth/Wi-Fi và chuyển tiếp tin cho nhau nhiều bước nhảy (A → B → C). Toàn bộ giao thức mesh, mã hóa và CRDT là code tự viết; chỉ tầng kết nối trực tiếp (1 bước) dùng Google Nearby Connections.

**Trạng thái ngày 07/10:** mọi tính năng tới tin nhắn thoại và video đã chạy và đã kiểm chứng. **Gọi thoại/video đã chạy thật trên emulator** (test vòng lặp CallEngine 4/4, gọi thử dội lại chính mình có hình và tiếng), **nhưng chưa gọi giữa hai điện thoại thật** (xem mục 5). 40 unit test + 4 test thiết bị cuộc gọi đều đạt.

**Cập nhật ngày 16/10:** đồng bộ giao diện iOS 26 từ `main` (`fb9c943`), giữ màu hệ thống và thanh điều hướng nổi ở cả chế độ sáng/tối. Tin nhắn hỗ trợ chạm để xem giờ, nhấn giữ để sao chép; trạng thái gửi nằm dưới bong bóng, mốc ngày/giờ xuất hiện khi đổi ngày hoặc cách nhau từ 15 phút. Đã sửa nhãn radar tránh ký hiệu Bắc, thông báo cuộc gọi che màn hình gọi, tương thích rung Android 7 và cấu hình bộ mã hóa Android 7–9. Kiểm thử phát hiện và đã sửa lỗi khung hình đến muộn làm hiện lại video sau khi tắt camera. Build debug, 40 unit test, lint (0 lỗi) và 4/4 bài `CallEngineLoopbackTest` đều đạt trên emulator.

---

## 1. Kiến trúc

```
ui/          Giao diện XML + ViewBinding
             MainActivity (3 tab) · MeshFragment · ChatsFragment · BoardFragment
             ConversationActivity · CallActivity · PeerSheet · ImageViewer · VideoPlayer
data/        Tầng Android
             MeshRuntime (chủ của mọi thứ) · MeshDatabase (SQLite) · LocationTracker
             ImageCompressor · VoiceRecorder · VideoCompressor · CallEngine · CallNotifier
service/     MeshService – foreground service giữ mesh chạy khi tắt màn hình
transport/   NearbyTransport – kết nối 1 bước (BLE, Bluetooth, Wi-Fi), tối đa 4 kết nối/máy
mesh/        LÕI GIAO THỨC – Kotlin thuần, không phụ thuộc Android, test được trên máy tính
  core/      Packet (định dạng nhị phân), NodeId, Geo, Transport interface, ByteIO
  crypto/    Identity (2 cặp khóa P-256), Crypto (ECDSA, ECDH, HKDF, AES-GCM)
  routing/   MeshRouter (định tuyến), Announce, RoutingModels
  crdt/      HLC, CrdtOp, CrdtStore (LWW-Map), CrdtReplica
  dm/        DirectMessenger (tin riêng, ảnh, thoại, video), SessionKeys
  call/      CallManager (máy trạng thái cuộc gọi)
  MeshNode   Ghép router + CRDT + tin nhắn + cuộc gọi, chạy trên 1 luồng duy nhất
```

Nguyên tắc: `mesh/` không import gì của Android, nên chạy được trong unit test với mạng giả lập (`FakeNetwork`). Tầng kết nối nằm sau interface `Transport`, có thể thay bằng BLE thuần hay LoRa mà không sửa phần trên.

---

## 2. Giao thức

### Gói tin (`mesh/core/Packet.kt`) – phiên bản giao thức hiện tại: **6**
```
magic(1) version(1) type(1) ttl(1) hops(1) id(8) src(8) dst(8) timestamp(8)
payloadLen(4) payload  sigLen(1) signature
```
- Mọi gói được **máy gốc ký ECDSA**. `ttl` và `hops` không nằm trong phần ký vì thay đổi ở mỗi bước.
- Riêng `CALL_MEDIA` không ký (quá nhiều gói mỗi giây), chỉ mã hóa AES-GCM bằng khóa cuộc gọi.
- Đổi định dạng thì tăng `VERSION`. Hai bản khác phiên bản sẽ hiện cảnh báo "máy ở gần đang dùng bản khác".

| Loại gói | Mã | Dùng cho |
|---|---|---|
| ANNOUNCE | 1 | Tự giới thiệu: khóa, tên, pin, GPS → dựng bảng định tuyến |
| UNICAST / ACK | 2 / 3 | Tin nhắn riêng mã hóa đầu-cuối và xác nhận |
| CRDT_OPS | 4 | Dữ liệu Kênh chung / Bảng tin mới, phát lan |
| SYNC_DIGEST / SYNC_OPS | 5 / 6 | Đồng bộ bù với hàng xóm (anti-entropy) |
| FILE_CHUNK / FILE_NACK | 7 / 8 | Mảnh tệp đính kèm 16 KB và báo mảnh thiếu |
| TRACE / TRACE_HOP / TRACE_REPLY | 9 / 10 / 11 | Dò đường |
| CALL_SIGNAL | 12 | Thiết lập cuộc gọi |
| CALL_MEDIA | 13 | Hình và tiếng của cuộc gọi |

### Định tuyến (`mesh/routing/MeshRouter.kt`)
- Kiểu distance-vector, lấy ý tưởng từ BATMAN. Mỗi máy phát ANNOUNCE mỗi 10–60 giây (mạng càng đông càng thưa).
- Nhận announce của máy X qua kết nối L thì ghi "tới X đi qua L, cách hops+1 bước". Chọn đường mới hơn (seq), rồi ngắn hơn. Tuyến hết hạn sau 3 chu kỳ + 5 giây.
- Bảng định tuyến chỉ nằm trong RAM, tự dựng lại sau 10–20 giây khi mở app.
- Gói không phải của mình → chuyển tiếp (`forward`). Có tuyến thì gửi đúng 1 kết nối, không có thì phát lan. Chống lặp bằng bộ nhớ 8192 ID gói.
- Gói chữ ký sai bị chặn ngay ở máy đầu tiên. Gói từ máy chưa biết khóa được giữ tạm 30 giây chờ announce.
- Di chuyển > 25 m thì phát announce sớm.

### Mã hóa (`mesh/crypto/`, `mesh/dm/SessionKeys.kt`)
- Mỗi máy có 2 cặp khóa P-256 (ký, trao đổi khóa). ID máy = SHA-256(khóa ký) → không mạo danh được.
- Khóa giữa 2 người = HKDF-SHA256(ECDH). Tin riêng dùng AES-256-GCM, header là AAD nên máy trung chuyển không đọc, không sửa được.
- Cuộc gọi: lời mời mang khóa ngẫu nhiên 256 bit, chỉ 2 người trong cuộc có.

### Đồng bộ CRDT (`mesh/crdt/`)
- LWW-Map dạng op-based, đồng hồ lai HLC để phân định ai ghi sau. Nhận theo thứ tự nào, trùng bao nhiêu lần cũng ra cùng kết quả.
- Version vector + anti-entropy: máy vào sau hoặc bị tách mạng tự bắt kịp, lan dây chuyền qua nhiều bước.
- Dùng cho Kênh chung (`chat`) và Bảng tin (`board`). "Tôi an toàn" dùng khóa `status-<ID>` nên báo lại sẽ ghi đè.

### Tệp đính kèm (`mesh/dm/DirectMessenger.kt`)
- Ảnh ≤ 512 KB, tin thoại ≤ 512 KB, video ≤ 2 MB. Cắt mảnh 16 KB, mỗi mảnh mã hóa riêng.
- Header mang SHA-256 và bản xem trước (sóng âm / ảnh thu nhỏ ≤ 12 KB).
- Người nhận báo mảnh thiếu (NACK) sau 4 giây im lặng; đủ mảnh thì kiểm SHA-256 rồi mới ACK.

### Cuộc gọi (`mesh/call/CallManager.kt`)
- Trạng thái: IDLE → OUTGOING / INCOMING → ACTIVE → ENDED.
- Lý do kết thúc: HANGUP, DECLINED, BUSY, NO_ANSWER (45 giây), LOST (12 giây không có tín hiệu), NO_ROUTE.
- Lời mời và "nhận máy" được gửi lại mỗi 2 giây tới khi bên kia phản hồi. Keepalive mỗi 2 giây.
- Gói hình/tiếng chỉ đi theo tuyến cố định, **không bao giờ phát lan**, rơi là bỏ.

---

## 3. Tính năng đã xong và đã kiểm chứng

| Tính năng | Kiểm chứng |
|---|---|
| Kết nối Nearby, mesh nhiều bước, tự tìm đường vòng | Test mô phỏng; A17 ↔ Xiaomi nhắn tin thật (1 bước) |
| Tin nhắn riêng mã hóa đầu-cuối, lưu chờ khi ngoài vùng phủ | Test mô phỏng + máy thật |
| Kênh chung, Bảng tin (SOS, An toàn, Nguồn hỗ trợ, Thông báo) | Test mô phỏng + emulator |
| Gửi ảnh (nén ≤ 200 KB, xoay EXIF) | Test mô phỏng (rớt 40% mảnh) + test thiết bị trên A17 |
| Tin nhắn thoại (giữ để nói, sóng âm) | Test mô phỏng + ghi âm thật trên emulator |
| Gửi video (nén 360p, ≤ 30 giây, ≤ 2 MB) | Test mô phỏng (2 MB, 3 bước, rớt 25%) + nén thật trên emulator |
| Radar: khoảng cách/hướng GPS thật, xoay theo la bàn | Ảnh chụp với dữ liệu giả lập |
| Báo "rất gần" khi khoảng cách < sai số GPS | Test với số liệu đo trên A17 (± 26,5 m trong nhà) |
| Màn hình Tìm người (mũi tên, khoảng cách, rung) | Ảnh chụp giả lập |
| Dò đường (traceroute) | Test mô phỏng (chuỗi, đường vòng) |
| Chạy nền khi tắt màn hình | Thử thật trên A17 ở chế độ Doze 2,5 phút |
| Giao diện iOS 26 (Be Vietnam Pro, xanh hệ thống, thanh nổi, sáng/tối) | Ảnh chụp emulator; chạm xem giờ và nhấn giữ sao chép tin nhắn |

---

## 4. Thiết bị và cách chạy

- **Thiết bị đã dùng:** Samsung Galaxy A17 (`R58YA0FNAAF`, Android 16), Xiaomi M2102J2SC (`13de0fe`, Android 13, ID mesh "Hieu" `34FD-C2F7`), emulator Pixel_9_Pro_XL.
- **Build:** `./gradlew :app:assembleDebug` (JAVA_HOME = JBR của Android Studio).
- **Unit test:** `./gradlew :app:testDebugUnitTest` → hiện có **40 test, đều đạt**.
- **Lint:** `./gradlew :app:lintDebug` → ngày 16/10: **0 lỗi**, còn 104 cảnh báo.
- **Test trên thiết bị, không làm mất dữ liệu:** cài `app-debug.apk` và `app-debug-androidTest.apk` bằng `adb install -r`, rồi chạy `adb shell am instrument -w -e class com.hieu.p2p.<TênTest> com.hieu.p2p.test/androidx.test.runner.AndroidJUnitRunner`. **Không dùng** `connectedDebugAndroidTest` trên máy thật, vì lệnh đó gỡ app sau khi chạy.
- **Màn hình debug để soát giao diện** (chỉ có trong bản debug):
  - `adb shell am start -S -n com.hieu.p2p/.ui.RadarPreviewActivity [--ef heading 60] [--ez select true] [--ez sheet true]`
  - `adb shell am start -S -n com.hieu.p2p/.ui.DemoChatActivity` – nạp cuộc chat mẫu
  - `adb shell am start -S -n com.hieu.p2p/.ui.CallPreviewActivity --es phase calling|ringing|incoming|active --ez video true|false` – mở màn hình cuộc gọi ở trạng thái chọn, không cần máy bên kia. Ở `active`, hình và tiếng của mình được dội lại như thể bên kia gửi (đi qua đủ đường nén → giải mã thật).
- **Test cuộc gọi trên thiết bị:** `adb shell am instrument -w -e class com.hieu.p2p.CallEngineLoopbackTest com.hieu.p2p.test/androidx.test.runner.AndroidJUnitRunner`
- **Số liệu khi đang gọi:** mỗi 5 giây log `MeshLink` in dòng `call N hop(s): audio X↑ Y↓ · video ...↑ (key ..) ...↓ · key requests .. · kbps · chất lượng`.
- **Đọc log:** `adb logcat -s MeshLink:I`. Trong app: biểu tượng đồng hồ → Nhật ký kết nối.
- **Lưu ý Xiaomi:** chặn adb điều khiển cảm ứng (cần bật "Gỡ lỗi USB (Cài đặt bảo mật)"); nên tắt tối ưu pin và bật Tự khởi chạy cho MeshLink.

---

## 5. Gọi thoại / video – đã chạy trên emulator, chờ thử 2 máy thật

**Đã viết xong, build đạt, unit test đạt:**
- `mesh/call/CallManager.kt` + router fast path cho `CALL_MEDIA`. 5 test mô phỏng: gọi video qua 2 bước, nghe chỉ tiếng, từ chối / bận / không ai nghe, gửi lại "nhận máy" khi bị rơi, mất sóng, không có đường.
- `data/CallEngine.kt`:
  - Tiếng: AudioRecord (VOICE_COMMUNICATION, khử vọng, khử ồn) → AAC 16 kHz 24 kbps → AudioTrack, cắt bớt khi dồn quá ~400 ms.
  - Hình: Camera2 → H.264 phần cứng ~320×240, 15 hình/giây. Cắt mảnh 12 KB; mất mảnh thì xin keyframe.
  - Bitrate tự điều chỉnh 80–450 kbps theo tỉ lệ mất gói; mạng quá yếu thì tự tắt hình, giữ tiếng.
- `data/CallNotifier.kt`: chuông, rung, thông báo toàn màn hình, nút Từ chối trên thông báo; bật/tắt CallEngine theo trạng thái.
- `ui/CallActivity.kt` + `activity_call.xml`: hình bên kia toàn màn hình, hình mình ở góc, các nút tắt mic, tắt hình, đổi camera, loa ngoài/loa trong, kết thúc; màn hình cuộc gọi đến có Nghe / Chỉ nghe tiếng / Từ chối; tắt màn hình khi áp tai (cảm biến tiệm cận).
- Nút gọi thoại và gọi video ở đầu màn hình chat riêng.
- `MeshService.callMode()`: thêm loại foreground service micro/camera khi đang gọi.
- Manifest: CAMERA, MODIFY_AUDIO_SETTINGS, FOREGROUND_SERVICE_MICROPHONE/CAMERA, USE_FULL_SCREEN_INTENT, WAKE_LOCK, VIBRATE.

**Ngày 07/10 – đã kiểm chứng trên emulator Pixel_9_Pro_XL:**
- Sửa 2 lỗi khiến **gọi video không bao giờ có hình**:
  - `MediaCodec.setCallback` bị gọi sau `configure` → codec không chạy chế độ bất đồng bộ. Đã chuyển lên trước.
  - Bộ nén H.264 của emulator từ chối `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` → `configure` lỗi. Đã bỏ khóa này (code vốn tự gắn SPS/PPS trước keyframe) và cho `configure` lùi dần: CBR + profile + level → bỏ từng cái → cấu hình trơn. Không cấu hình được thì cuộc gọi tiếp tục chỉ có tiếng.
- `CallEngineLoopbackTest` (4 test, đạt): tiếng hai chiều (~15 khung/giây mỗi chiều); 30 khung hình giải mã được ở bên kia, kích thước 240×320 đã xoay; mất hình 0,7 giây → bên nhận xin keyframe, bên gửi phát keyframe mới, hình phục hồi trong < 1,5 giây; tắt camera → bên kia ẩn hình, bên gửi ngừng gửi.
- Chụp màn hình CallActivity: đang gọi, đổ chuông, cuộc gọi đến (kèm thông báo Từ chối/Nghe), đang gọi thoại, đang gọi video (hình bên kia toàn màn hình, hình mình ở góc, soi gương với camera trước), đổi camera trước ↔ sau. Hình không méo, cắt vừa màn hình.
- Màn hình cuộc gọi đến giờ ghi rõ "Đang gọi video cho bạn" / "Đang gọi thoại cho bạn".
- `CallEngine.stats` đếm khung gửi/nhận; `CallNotifier` log số liệu mỗi 5 giây khi đang gọi.

**Chưa kiểm chứng:**
- Chưa gọi giữa 2 điện thoại thật (độ trễ, tỉ lệ mất gói, tự hạ bitrate qua Nearby).
- Khử vọng (AEC) và tai nghe/loa trong — emulator không thử được.
- Cuộc gọi đến khi màn hình khóa trên máy thật.

---

## 6. Việc cần làm tiếp (ngày mai)

1. Cài bản mới nhất lên **cả A17 và Xiaomi** (giao thức v6, bản cũ không nói chuyện được).
2. ~~Test vòng lặp CallEngine~~ – xong 07/10. Chạy lại `CallEngineLoopbackTest` trên A17 và Xiaomi (bộ nén phần cứng thật, khác emulator).
3. ~~Chụp màn hình CallActivity~~ – xong 07/10.
4. Gọi thật A17 ↔ Xiaomi: gọi thoại trước, rồi gọi video. Đọc log `MeshLink`, đo độ trễ và tỉ lệ mất gói.
5. Kiểm tra cuộc gọi đến khi màn hình khóa (full-screen intent trên Android 14+ có thể cần cấp quyền thủ công).
6. Kiểm tra quay video qua camera hệ thống sau khi app khai báo quyền CAMERA.
7. Nếu có máy thứ ba: thử nhắn tin, dò đường và gọi qua 2 bước (máy 1 và máy 3 ngoài tầm sóng của nhau).

---

## 7. Giới hạn đã biết

- Chỉ máy gửi giữ tin chờ gửi lại; máy trung chuyển chưa giữ hộ (DTN).
- Thao tác CRDT chưa ký riêng theo người tạo; mô hình tin các thành viên trong mạng.
- Chưa có forward secrecy; khóa nằm trong SharedPreferences, chưa dùng Android Keystore.
- Lịch sử thao tác CRDT chưa được nén.
- Cần Google Play Services (Nearby). Máy Huawei đời mới không chạy được.
- GPS trong nhà lệch 20–50 m; dưới ~10 m không chỉ được hướng (không có UWB).
- Tắt nguồn hoặc khởi động lại máy thì phải mở app lại; chưa đo mức hao pin.
- Gọi video thực tế chỉ ổn ở 1 bước có Wi-Fi; qua Bluetooth hoặc nhiều bước nên gọi thoại.
- Kênh chung chỉ cho gửi chữ (phát tệp cho cả mạng sẽ gây nghẽn).
