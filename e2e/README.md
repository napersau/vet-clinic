# Kiểm thử end-to-end (v4.0)

Chạy qua API Gateway (`http://localhost:8080`) trên hệ thống Docker thật, tự đối chiếu `PASS` / `FAIL`. Mỗi kịch bản in dòng cuối
`N đạt, M lỗi`. Bộ e2e của thiết kế v2 (hàng đợi sảnh 3 bậc, Khóa ưu tiên, hóa đơn gộp, `dispense`) đã bị **xóa** ở Phase 7.

| File | Phạm vi | Số kiểm tra |
|---|---|---|
| `backend_v4.mjs` | **Luồng chuẩn thu trước – phát phiếu (17/11/2026)**: check-in lập phí khám (chưa có phiếu, `start-exam` → `CLIN_1314`) → thu phí khám → phiếu `DOCTOR` → gọi số → khám → CBC + X-quang (chưa nộp tiền thì chưa vào hàng đợi phòng) → thu XN + CĐHA → phiếu Lab / CĐHA → bắt đầu phiếu, nhập kết quả → phiếu ưu tiên 1 đứng đầu hàng đợi bác sĩ → kê đơn → chốt → không còn khoản dịch vụ chờ thu, xác nhận T10 với danh sách rỗng; đối chiếu `v_service_invoice_amounts` = **530.000**, `v_medicine_invoice_amounts` = **157.500**, Clavamox 200 → 190; dịch vụ tại phòng chỉ thu khi chốt ca (`BILL_1409`); ca cấp cứu có phiếu ngay (priority 2); hủy XN/CĐHA đã thu: phòng khám lỗi → `REFUNDED`, khách tự hủy → giữ `PAID`; **EX-15**, **EX-16** (kể cả 5 yêu cầu song song); **đua `RESULTS_READY`**; **6 quầy thu phí khám đồng thời không trùng số phiếu**; **Gọi theo từng phiếu** (phiếu vắng giữ vị trí, phiếu thứ 3–4 vẫn gọi được, "Gọi lại", `queue_order` đã xóa); **công nợ**; **đặt lịch đồng thời**; **refresh token dùng lại**; phân quyền, `/internal/**` bị chặn; **Transactional Outbox (v4 Phase 1)**: 4 CSDL có `outbox_events`, mọi dòng outbox `PUBLISHED`, đủ 6 loại sự kiện đi qua outbox, phiếu/gọi số gửi thẳng (D9) | 76 |
| `notification_v4.mjs` | 10 sự kiện (thêm `queue.ticket_issued`: chủ nuôi nhận số phiếu, bác sĩ / KTV được làm mới) → thông báo đúng người (chủ nuôi, bác sĩ, KTV, lễ tân, Admin) qua RabbitMQ thật; `POST /notifications/{id}/read` (riêng từng người, idempotent, 404); `send-email` đã bỏ; email (chỉ khi truy cập được Mailpit) | 28 |
| `fe_smoke_v4.mjs` | **Giao diện thật** trên Chrome headless (Chrome DevTools Protocol, gói `ws` có sẵn trong `fe/node_modules`): khung giờ công khai, đặt lịch, tiếp nhận kèm chọn bác sĩ, thu phí khám → phát phiếu, Gọi số / Gọi lại, thu tiền XN / CĐHA → phiếu phòng, dịch vụ tại phòng, CLS Lab/CĐHA (có ảnh), giai đoạn 2, kê đơn (cảnh báo tồn), thu dịch vụ tại phòng khi chốt ca + giảm giá, bán thuốc, bệnh án + thông báo của chủ nuôi, báo cáo hai nguồn, phân quyền; in mọi lỗi console / HTTP ≥ 400 | 69 |
| `features_v4.mjs` | **Chức năng ngoài luồng khám chính** (216 kiểm tra): tài khoản (tự đăng ký, hồ sơ, đổi / đặt lại mật khẩu, tạo - khóa - mở khóa - đổi vai trò, phân quyền lễ tân, chặn dò mật khẩu 429); danh mục (loài, dịch vụ + VAT, chỉ số xét nghiệm + khoảng tham chiếu + gói, **đánh giá LOW/NORMAL/HIGH**, nhập thiếu chỉ số bị từ chối); thú cưng và quyền xem; lịch hẹn (đặt, duyệt, đổi giờ, hủy, **quy tắc 2 giờ**, NO_SHOW, tiếp nhận theo lịch → `CHECKED_IN`, báo cáo); quản lý đợt khám (phân công / gỡ / đổi, cấp cứu, T03, T05, T11, T12, T14 bác sĩ + Admin, dịch vụ tại phòng, giảm giá, thu một phần, `CLIN_1310`, ảnh CĐHA: kiểm tra nội dung tệp + quyền tải); báo cáo; danh mục thuốc/kho; **gộp ca CĐHA** (hai dịch vụ cùng đợt khám nằm liền nhau; Gọi / Gọi lại không đổi thứ tự); **WebSocket/STOMP thật** (JWT, cấm đăng ký kênh vai trò khác, cấm SEND, nhận thông báo thời gian thực) | 165 |
| `fe_pages_v4.mjs` | **Quét mọi trang giao diện** theo 6 vai trò (marker + không khung lỗi + không lỗi console/HTTP) và các thao tác chưa có trong smoke: lễ tân tạo khách (mật khẩu tạm hiện một lần, khách đăng nhập được), chủ nuôi đổi / hủy lịch, lễ tân duyệt lịch, Admin tạo - khóa - mở khóa bác sĩ, thêm loài, thêm dịch vụ có VAT | 48 |
| `v4_resilience.mjs` | **Ngoại lệ phân tán và phi chức năng v4.0 (bước 8.3)**, tự tạo dữ liệu riêng nên chạy lại được: **EX-17** (khóa Bác sĩ còn phiếu chờ: `CLIN_1316` ngay, phiếu về danh sách chờ giữ số và thứ tự); **EX-18 / NFR-09** (TẮT RabbitMQ rồi thu tiền, chốt ca có đơn thuốc, tiếp đón → vẫn thành công, bật lại → phiếu theo giờ thu, hóa đơn thuốc nháp, reporting đủ, outbox về `PUBLISHED`; TẮT Redis → `CLIN_1318`, thu tiền vẫn thành công, phiếu phát đúng một); **EX-19** (giao lại 5 sự kiện, không tạo thêm gì); **EX-20** (đổi giá dịch vụ / thuốc khi ca đang mở); **NFR-10** (10 thao tác hàng đợi đồng thời: không `SYS_1005`, lệnh Lua ≤ 50 ms theo `commandstats` / `SLOWLOG` của Redis; danh sách đợt khám và lịch hẹn vẫn 200 khi TẮT auth / appointment / clinical = 0 lời gọi chéo; báo cáo tháng ≤ 2 giây). **DỪNG / BẬT LẠI container thật**, mất ~10 phút | 59 |
| `auth.sh` | auth-service: đăng nhập, refresh, khóa tài khoản, endpoint nội bộ (kịch bản quan sát, không đếm PASS; tạo thêm tài khoản bác sĩ thử) | – |

## Chạy

```bash
docker compose down -v && docker compose up -d --build   # dữ liệu seed sạch; chờ mọi container healthy (lần đầu ~10 phút do máy chậm)
bash e2e/run_all.sh                                       # backend -> notification -> giao diện, in tổng kết, mã thoát 1 nếu có lỗi
# hoặc từng kịch bản:
node e2e/backend_v4.mjs
node e2e/notification_v4.mjs
node e2e/fe_smoke_v4.mjs
node e2e/features_v4.mjs        # sau backend_v4 (tạo thêm bác sĩ làm đổi sức chứa khung giờ)
node e2e/fe_pages_v4.mjs
node e2e/v4_resilience.mjs      # CUỐI CÙNG: tắt / bật lại RabbitMQ, Redis, auth, appointment, clinical
```

Yêu cầu: Node 18+ (dùng `fetch`, `FormData`), Docker Compose (script đối chiếu VIEW/bất biến kho bằng `docker compose exec mysql-server mysql`,
đọc `MYSQL_ROOT_PASSWORD` từ `.env` ở thư mục gốc), Chrome cài sẵn cho `fe_smoke_v4` (hoặc đặt `CHROME_PATH`). Giao diện lấy ở cổng 3000
(image `frontend` của compose); nếu cổng trống, script tự phục vụ thư mục `fe/build` (cần `npm run build` trong `fe/`). Biến tùy chọn:
`API_URL`, `FE_PORT`, `MAILPIT_URL`.

## Lưu ý

- Hàm dùng chung nằm ở `lib/api.mjs` (`call`, `login`, `check`, `step`, `finish`, `sql`…). **Không viết cứng id số**: id dữ liệu seed tra
  qua `lib/ids.mjs` theo mã ổn định — tài khoản theo `username`, thú cưng theo `petCode`, loài / dịch vụ / thuốc theo `code`
  (`seedIds()` trả `ID.user.bs_tien`, `ID.pet.lu`, `ID.service.LAB_CBC`, `ID.medicine.MED_AMOX`…; câu SQL đối chiếu dùng câu con theo mã).
  Khi khóa đổi sang UUIDv7 (lộ trình v4) chỉ phải sửa `lib/ids.mjs` và vài câu SQL.
- Các kịch bản **tạo và để lại dữ liệu nghiệp vụ** (đợt khám, hóa đơn, lịch hẹn, thú cưng thử, kho thuốc bị trừ / điều chỉnh) và dựa vào trạng
  thái của dữ liệu seed (Lu chưa có đợt khám mở, Clavamox tồn 200, `Admin@123`). Muốn chạy lại phải reset CSDL (`docker compose down -v`).
  Thứ tự `backend` → `notification` → `giao-dien` trong `run_all.sh` đã tính tới tồn kho còn lại giữa các kịch bản.
- `fe_smoke_v4` tự tạo 4 tài khoản nhân sự (`0990000001..4`, mật khẩu `Smoke@1234`) nếu chưa có.
- Lịch sử thông báo của notification-service nằm trong bộ nhớ nên còn lại giữa các lần chạy.
- Kiểm tra email cần Mailpit truy cập được (cổng 8025 chỉ mở khi dùng `docker-compose.dev.yml`); nếu không, phần email được bỏ qua.
- `features_v4.mjs` chạy lại được khi đặt `E2E_CLEAN=1`: khi đó đầu script HỦY các đợt khám dở dang của bác sĩ `bs_tien` / `bs_an` / `bs_mai` bằng một câu SQL. **Không** đặt biến này trên CSDL đang demo thủ công (ca đang chờ sẽ bị hủy); mặc định script không dọn gì. Kiểm tra email cần Mailpit truy cập được (dùng `-f docker-compose.dev.yml`).
- Việc đặt lịch đồng thời dựa vào sức chứa mỗi khung = số bác sĩ ACTIVE × 2: chạy sau `auth.sh` (tạo thêm bác sĩ) sẽ làm đổi con số 6.
