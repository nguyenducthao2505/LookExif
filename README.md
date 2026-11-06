# LookExif

App Android để xem thông số chụp của ảnh Sony (thử với Sony A7C II):

- **Creative Look:** kiểu Look, Contrast, Highlights, Shadows, Fade, Saturation, Sharpness, Sharpness Range, Clarity
- **Cân bằng trắng:** chế độ WB, nhiệt độ màu, WB Shift A–B và G–M (chính xác tới 0,25 bước)
- **Phơi sáng:** chế độ chụp, tốc độ, khẩu độ, ISO, bù sáng, đo sáng
- **Dải động và khử nhiễu:** DRO, Auto HDR, High ISO NR
- **Máy và ống kính:** thân máy, ống kính, tiêu cự, thời gian chụp

Định dạng đọc được: JPEG, HEIF/HEIC, ARW. App không cần quyền truy cập bộ nhớ và không dùng mạng.

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
