# MeshLink log lines (tag `MeshLink`)

All come through `MeshNode.log` → `android.util.Log.i("MeshLink", …)`. Text is Vietnamese; match on the stable prefix.

| Line (prefix) | Source | Means |
|---|---|---|
| `Khởi động nút <id>` | MeshNode | node started; `<id>` is this phone's short NodeId — map serial → id from it |
| `Đang quảng bá (advertising)` / `Đang dò tìm thiết bị (discovery)` | NearbyTransport | radio side is up |
| `Không quảng bá được:` / `Không dò tìm được:` | NearbyTransport | radio failed to start — usually permissions, Bluetooth/Wi‑Fi/location off, or Play Services |
| `Kết nối trực tiếp mới: <link>` | MeshRouter | a one-hop link formed |
| `Kết nối <name> thất bại:` / `Kết nối <endpoint> không thành công (<code>)` | NearbyTransport | link attempt failed; a few are normal while radios negotiate, a steady stream is not |
| `Phát hiện nút "<name>" (<id>) cách <n> bước` | MeshRouter | first route to a node; `<n>` is the hop count — the multi-hop evidence |
| `Mất kết nối <id>, gỡ <k> tuyến` | MeshRouter | link lost and dependent routes removed |
| `Hết hạn <k> tuyến: …` | MeshRouter | routes timed out (node left without a clean disconnect) |
| `Máy kề bên dùng giao thức v<a>, máy này v<b>…` | MeshRouter | version mismatch — redeploy every phone |
| `Đồng bộ: gửi <k> thao tác cho hàng xóm` / `Đồng bộ: nhận <k> thao tác mới` | MeshNode | CRDT anti-entropy moved ops |
| `Dò đường tới <id>` → `Dò đường xong: <n> bước, khứ hồi <ms> ms` | MeshNode | traceroute started from the UI and finished |
| `Dò đường: hết … giây, không có phản hồi từ đích…` | MeshNode | traceroute timed out |
| `Gửi tới <link> lỗi:` | NearbyTransport | a send on a link failed |
| `Lỗi:` | MeshNode | an exception on the mesh-core thread — always worth reading |
| `call <n> hop(s): …` | CallNotifier | call stats every 5 s, see `calls.md` |

If a line you expect is missing, grep the source for its prefix before concluding — wording changes with the code.
