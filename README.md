# LookExif

App Android để xem thông số chụp của ảnh Sony (thử với Sony A7C II):

- **Creative Look:** kiểu Look, Contrast, Highlights, Shadows, Fade, Saturation, Sharpness, Sharpness Range, Clarity
- **Cân bằng trắng:** chế độ WB, nhiệt độ màu, WB Shift A–B và G–M (chính xác tới 0,25 bước)
- **Phơi sáng:** chế độ chụp, tốc độ, khẩu độ, ISO, bù sáng, đo sáng
- **Dải động và khử nhiễu:** DRO, Auto HDR, High ISO NR
- **Máy và ống kính:** thân máy, ống kính, tiêu cự, thời gian chụp

Định dạng đọc được: JPEG, HEIF/HEIC, ARW. App không cần quyền truy cập bộ nhớ và không dùng mạng.

## Tự động AI (từ v1.26)

Trong màn **Chỉnh ảnh**, mục **Tự động AI**:

- **✦ Tự động:** một chạm bật chuẩn hóa ánh sáng 70% + màu da 75% (và giống ảnh mẫu 80% nếu đã chọn ảnh mẫu).
- **Bản đồ da AI:** tách người khỏi nền bằng ML Kit Selfie Segmentation (offline) kết hợp màu da lấy mẫu ở má, bỏ mắt, mày, môi. Bấm **Bản đồ da** để xem vùng được chỉnh (phủ màu cam).
- **Chuẩn hóa ánh sáng:** cân sáng theo da mặt (L* ≈ 66), da ngăm trong cảnh đủ sáng giữ độ sâu; đường cong giữ điểm trắng nên vùng sáng không cháy.
- **Màu da tự động:** chỉ tác động trong bản đồ da — đưa tông da về 50–60°, giữ độ đậm theo loại da (da sáng không ửng, da ngăm không bệch xám), làm đều vết ửng đỏ.
- **Match màu ảnh mẫu:** học tông sáng, màu nền và màu da của một ảnh bạn thích; da xử lý riêng nên không bị nhuộm màu nền. Ảnh mẫu được nhớ để dùng trong **Chỉnh hàng loạt**.
- **Chỉnh hàng loạt:** mỗi ảnh được tính riêng (ảnh tối kéo sáng, ảnh sáng hạ xuống), nên cả bộ ảnh đều màu.

### Nâng cấp v1.27

- **Da AI thật:** MediaPipe Selfie Multiclass phân lớp da mặt / da người / tóc / áo / nền; người nhỏ trong ảnh toàn thân hay ảnh nhóm được phân tích thêm trên vùng cắt quanh mặt. Máy không chạy được thì tự lùi về ML Kit.
- **Đồng bộ cả bộ ảnh** (Chỉnh hàng loạt): quét cả bộ trước, đưa độ sáng da và màu da mọi ảnh về một mức chung; người khác kiểu da không bị ép theo.
- **Học gu riêng:** ★ Học gu từ ảnh đẹp — chọn 10–20 ảnh, app học độ sáng da, tông, độ đậm và độ sáng cảnh để làm mục tiêu cho nút Tự động.
- **Match ảnh mẫu theo vùng:** riêng vùng tối, trung tính, vùng sáng, cây lá và trời.
- **Thông số Sony:** cân sáng theo người được lấy nét (ảnh nhóm), tôn trọng WB Shift A/B đã đặt, giữ vùng sáng kỹ hơn khi máy bật DRO/HDR.
- **Ngược sáng:** nhận ra nền sáng hơn mặt nhiều, nâng riêng người, giữ trời và nền.

Mô hình `selfie_multiclass_256x256.tflite` được workflow tải về lúc build (không cần để trong repo).

## Build APK bằng GitHub Actions

1. Tạo repo mới trên GitHub, ví dụ `LookExif`.
2. Tải toàn bộ nội dung thư mục này lên repo, **bao gồm cả thư mục ẩn `.github`**.
3. Vào tab **Actions**. Workflow **Build APK** sẽ tự chạy, mất khoảng 3–5 phút.
4. Mở lần chạy có dấu tích xanh. Ở mục **Artifacts**, tải `LookExif-apk` về (file .zip), giải nén ra `LookExif.apk` rồi cài lên điện thoại.

## Cách dùng

- Mở app, bấm **Chọn ảnh** (chọn được nhiều ảnh cùng lúc).
- Hoặc từ Thư viện ảnh, bấm **Chia sẻ → LookExif**.
- Bấm **Sao chép** để copy thông số rồi dán cho Claude kiểm tra.

## Lưu ý

- Phải dùng **file gốc** từ máy ảnh. Ảnh gửi qua Zalo, Messenger hoặc Facebook đã bị xóa thông số.
- Khi chuyển ảnh bằng Creators' App, chọn kích thước **Gốc (Original)**. Bản 2MP có thể thiếu thông số Sony.
- Máy không ghi chế độ AWB *ưu tiên môi trường / ưu tiên trắng* và số vị trí nhớ (1, 2, 3, M1–M4) ở dạng đọc được.
