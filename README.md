# MeshLink — P2P Offline Mesh Network (Android, XML Views)

Nhắn tin & đồng bộ dữ liệu **không cần Internet**: điện thoại tự kết nối với nhau qua Bluetooth/BLE/Wi-Fi
(Google Nearby Connections) và chuyển tiếp gói tin nhiều bước nhảy (A → B → C → …).

## Kiến trúc

```
ui/ (XML + ViewBinding)    MainActivity · MeshFragment · ChatsFragment · BoardFragment · ConversationActivity
service/MeshService        Foreground service giữ nút luôn chuyển tiếp khi tắt màn hình
data/                      MeshRuntime (identity, luồng giao thức, tick 2s) · SQLite · model Bảng tin/Kênh chung
transport/NearbyTransport  Lớp liên kết: P2P_CLUSTER, tối đa 4 liên kết trực tiếp/máy
mesh/  (Kotlin thuần, không phụ thuộc Android — test được trên JVM)
  core/      Packet (định dạng nhị phân), NodeId, Transport interface
  crypto/    ECDSA P-256, ECDH P-256 + HKDF-SHA256, AES-256-GCM
  routing/   MeshRouter — distance-vector kiểu BATMAN + flooding có chống lặp
  crdt/      HLC + LWW-Map op-based + version vector anti-entropy
  dm/        Tin nhắn riêng E2E, ACK, store-and-forward
  MeshNode   Ghép tất cả, chạy đơn luồng, xuất StateFlow cho UI
```

### Giao thức
| Gói | Mục đích |
|---|---|
| `ANNOUNCE` | Mỗi nút flood định kỳ (10–60s, tự giãn theo kích thước mạng): seq, khóa công khai, tên. Người nhận ghi "next hop" → bảng định tuyến. |
| `UNICAST`/`ACK` | Tin nhắn riêng đi theo bảng định tuyến; không có tuyến thì flood. ACK đầu-cuối, gửi lại theo backoff. |
| `CRDT_OPS` | Thao tác dữ liệu mới, flood toàn mạng. |
| `SYNC_DIGEST`/`SYNC_OPS` | Anti-entropy 1 bước: trao version vector, gửi phần còn thiếu; lan dây chuyền khi nút vừa bắt kịp. |

- **Định danh tự chứng thực**: NodeId = SHA-256(khóa ký)[0..8]. Mọi gói đều được ký; relay xác minh trước khi chuyển tiếp → gói giả mạo/sửa đổi bị chặn ngay bước đầu.
- **E2E**: khóa phiên = HKDF(ECDH), header (gửi/nhận/id) là AAD của AES-GCM → relay định tuyến được nhưng không đọc/sửa/đổi địa chỉ được.
- **CRDT**: ghi đồng thời giải quyết bằng HLC (wall, counter, node) — mọi máy hội tụ cùng trạng thái bất kể thứ tự nhận gói.

## Chạy
- Android Studio → Run trên **2+ điện thoại thật** có Google Play Services (emulator không có radio thật).
- Bấm **Bật mạng lưới**, cấp quyền Bluetooth/Wi-Fi lân cận/Vị trí.
- Test giao thức (mô phỏng chuỗi, vòng, phân mảnh, mạng 30 nút): `./gradlew :app:testDebugUnitTest`

## Giới hạn hiện tại / lộ trình
- Store-and-forward chỉ ở máy gửi (chưa lưu giữ hộ tại relay kiểu DTN).
- Op CRDT tin cậy theo chữ ký gói của hàng xóm; chưa ký từng op theo người tạo.
- Chưa có forward secrecy (khóa ECDH dài hạn); khóa lưu trong SharedPreferences, chưa dùng Android Keystore.
- Op log chưa nén (compaction); phù hợp hàng nghìn bản ghi.
- Cần Google Play Services (Nearby). Có thể thay lớp `Transport` bằng Wi-Fi Aware/BLE GATT thuần.
