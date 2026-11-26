// Nhãn tiếng Việt + tông màu cho các enum của backend. tone: green | blue | amber | red | violet | gray
export const VISIT_STATUS = {
  WAITING: { label: 'Chờ khám', tone: 'amber' },
  IN_PROGRESS: { label: 'Đang khám', tone: 'blue' },
  WAITING_RESULTS: { label: 'Chờ kết quả CLS', tone: 'violet' },
  RESULTS_READY: { label: 'Đã có kết quả', tone: 'green' },
  WAITING_PAYMENT: { label: 'Chờ thanh toán', tone: 'amber' },
  COMPLETED: { label: 'Hoàn tất', tone: 'green' },
  CANCELLED: { label: 'Đã hủy', tone: 'gray' },
};
export const APPOINTMENT_STATUS = {
  PENDING: { label: 'Chờ xác nhận', tone: 'amber' },
  CONFIRMED: { label: 'Đã xác nhận', tone: 'blue' },
  CHECKED_IN: { label: 'Đã tiếp nhận', tone: 'green' },
  CANCELLED: { label: 'Đã hủy', tone: 'gray' },
  NO_SHOW: { label: 'Vắng mặt', tone: 'red' },
};
export const BOOKING_CHANNEL = { ONLINE: 'Trực tuyến', PHONE: 'Điện thoại', WALK_IN: 'Tại quầy' };
export const SERVICE_DETAIL_STATUS = {
  PENDING: { label: 'Chờ thực hiện', tone: 'amber' },
  IN_PROGRESS: { label: 'Đang thực hiện', tone: 'blue' },
  COMPLETED: { label: 'Hoàn thành', tone: 'green' },
  CANCELLED: { label: 'Đã hủy', tone: 'gray' },
};
// Trạng thái con của dịch vụ cận lâm sàng trong hàng đợi (SUY RA từ lịch sử chỉ định, không có cột riêng): QUEUED/SAMPLING/ANALYZING
export const QUEUE_STAGE = {
  QUEUED: { label: 'Đang chờ', tone: 'gray' },
  SAMPLING: { label: 'Đang lấy mẫu', tone: 'blue' },
  ANALYZING: { label: 'Đang phân tích', tone: 'violet' },
};
// Huy hiệu liên phòng trên hàng đợi CĐHA (EX-09): dịch vụ Lab của cùng ca
export const LAB_BADGE = {
  SAMPLING: { label: 'Lab đang lấy mẫu', tone: 'amber' },
  SAMPLE_COLLECTED: { label: 'Đã lấy mẫu, sẵn sàng chụp', tone: 'green' },
};
export const SERVICE_CATEGORY = {
  EXAMINATION: 'Khám tổng quát', VACCINATION: 'Tiêm phòng', DEWORMING: 'Tẩy giun', EXTERNAL_PARASITE: 'Ký sinh trùng ngoài da',
  INJECTION_INFUSION: 'Tiêm / truyền dịch', LABORATORY: 'Xét nghiệm', IMAGING: 'Chẩn đoán hình ảnh', SURGERY: 'Phẫu thuật', PROCEDURE: 'Thủ thuật',
};
/** Nhóm dịch vụ do Admin tạo thêm không có nhãn cố định: hiện mã nhóm. */
export const serviceCategoryLabel = (code) => SERVICE_CATEGORY[code] || code;
export const DOCTOR_STATUS = {
  AVAILABLE: { label: 'Rảnh', tone: 'green' },
  BUSY: { label: 'Đang bận', tone: 'red' },
};
export const PAYMENT_STATUS = {
  PENDING: { label: 'Chờ thanh toán', tone: 'amber' },
  PAID: { label: 'Đã thanh toán', tone: 'green' },
  CANCELLED: { label: 'Đã hủy', tone: 'gray' },
  REFUNDED: { label: 'Đã hoàn tiền', tone: 'blue' },
};
/** Phiếu xếp hàng (SM-07): 2 = cấp cứu, 1 = quay lại với kết quả CLS, 0 = thường. */
export const TICKET_PRIORITY = { 2: { label: 'Cấp cứu', tone: 'red' }, 1: { label: 'Có kết quả', tone: 'green' }, 0: { label: 'Thường', tone: 'gray' } };
export const QUEUE_TYPE = { DOCTOR: 'Phòng khám bác sĩ', LABORATORY: 'Phòng xét nghiệm', IMAGING: 'Phòng CĐHA' };
export const LAB_EVALUATION = {
  LOW: { label: 'Thấp', tone: 'amber' },
  NORMAL: { label: 'Bình thường', tone: 'green' },
  HIGH: { label: 'Cao', tone: 'red' },
};
export const INVENTORY_TYPE = { IMPORT: 'Nhập kho', EXPORT_SALE: 'Bán thuốc', ADJUSTMENT: 'Điều chỉnh' };
export const PET_GENDER = { MALE: 'Đực', FEMALE: 'Cái', UNKNOWN: 'Chưa rõ' };
export const ACCOUNT_STATUS = {
  ACTIVE: { label: 'Đang hoạt động', tone: 'green' },
  LOCKED: { label: 'Đã khóa', tone: 'red' },
  INACTIVE: { label: 'Ngừng hoạt động', tone: 'gray' },
};

// Mã lỗi nghiệp vụ (Quy chuẩn §5.1) -> câu hướng dẫn thân thiện hơn thông báo gốc của máy chủ. Chỉ các mã cần chỉ dẫn thao tác riêng.
export const ERROR_HINTS = {
  AUTH_1107: 'Tài khoản đã bị khóa hoặc ngừng sử dụng. Vui lòng liên hệ quản trị viên.',
  AUTH_1108: 'Tài khoản Bác sĩ phải có hồ sơ chuyên môn (số chứng chỉ hành nghề).',
  CLIN_1302: 'Bạn đang khám một ca khác. Hãy chốt hoặc kết thúc ca hiện tại trước khi gọi số tiếp theo.',
  CLIN_1303: 'Hàng đợi hiện không còn bệnh nhân nào.',
  CLIN_1306: 'Thú cưng này đang có một đợt khám chưa kết thúc.',
  CLIN_1307: 'Cần nhập chẩn đoán trước khi chốt ca.',
  CLIN_1308: 'Cần có ít nhất một lần đo sinh hiệu đủ cả cân nặng và thân nhiệt trước khi chỉ định cận lâm sàng hoặc chốt ca.',
  CLIN_1309: 'Còn dịch vụ tại phòng chưa bấm “Đã thực hiện” hoặc chưa hủy.',
  CLIN_1310: 'Phí khám và dịch vụ khách tự hủy không được hoàn tiền.',
  CLIN_1311: 'Ca này đã được phân công cho bác sĩ khác.',
  CLIN_1312: 'Không tìm thấy phiếu xếp hàng.',
  CLIN_1313: 'Phiếu này không còn ở trạng thái chờ (đã bắt đầu, xong hoặc đã hủy).',
  CLIN_1314: 'Ca chưa có phiếu xếp hàng: khách cần nộp phí khám tại quầy thu ngân trước.',
  CLIN_1315: 'Dịch vụ này chưa có giá đang áp dụng. Hãy nhờ quản trị viên thiết lập giá.',
  CLIN_1316: 'Bác sĩ được chọn đã bị khóa hoặc ngừng làm việc. Hãy chọn bác sĩ khác.',
  CLIN_1317: 'Hồ sơ khám đã khóa (đợt khám đã chốt) nên không sửa được. Bác sĩ phụ trách có thể mở lại hồ sơ.',
  CLIN_1318: 'Hàng đợi tạm thời không truy cập được. Vui lòng thử lại sau ít giây.',
  BILL_1402: 'Số tiền giảm giá vượt quá giá trị của hóa đơn này.',
  BILL_1403: 'Hóa đơn này đã được thanh toán.',
  BILL_1408: 'Hóa đơn này đã bị hủy.',
  BILL_1409: 'Dịch vụ làm tại phòng và tiền thuốc chỉ thu khi Bác sĩ đã chốt chuyên môn.',
  BILL_1410: 'Thời điểm hiệu lực của giá mới phải sau thời điểm bắt đầu của giá đang áp dụng.',
  PHARM_1406: 'Kho không đủ thuốc để bán. Hãy sửa số lượng trong hóa đơn hoặc báo kho nhập thêm.',
  PHARM_1407: 'Đơn thuốc này đã lập hóa đơn thuốc rồi (mỗi đơn chỉ một hóa đơn).',
  PHARM_1411: 'Đơn thuốc đã khóa (ca đã chốt hoặc đã có hóa đơn thuốc) nên không sửa được.',
  PHARM_1412: 'Thuốc này chưa có giá bán đang áp dụng. Hãy nhờ quản trị viên thiết lập giá.',
  SYS_1005: 'Dữ liệu vừa được người khác thay đổi. Hãy tải lại rồi thao tác lại.',
};
export const errorText = (problem) => ERROR_HINTS[problem?.code] || problem?.message || 'Thao tác không thành công.';

export const labelOf = (map, key) => (map[key]?.label ?? map[key] ?? key ?? '—');
export const toneOf = (map, key) => map[key]?.tone ?? 'gray';
