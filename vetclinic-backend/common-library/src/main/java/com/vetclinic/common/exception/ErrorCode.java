package com.vetclinic.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Danh mục mã lỗi dùng chung toàn hệ thống.
 * Nguồn chuẩn: QUY_CHUAN_THIET_KE_VA_LAP_TRINH_BACKEND_SPRINGBOOT.md v4.0, mục 5.1 (49 mã; {@code ErrorCodeCatalogTest} giữ đúng danh sách
 * này). Số trong docs được ưu tiên; mã chỉ có trong code thì nằm cuối nhóm. Đánh số lại ngày 12/11/2026 (v3, bước 1.1) và
 * 20/11/2026 (nhường AUTH_1107/1108, CLIN_1315-1318, PHARM_1412 cho docs v4.0).
 */
@Getter
public enum ErrorCode {
    // 1. Hệ thống chung (SYS: 1000 - 1099)
    UNCATEGORIZED_EXCEPTION("SYS_1000", "Lỗi hệ thống không xác định, vui lòng thử lại sau", HttpStatus.INTERNAL_SERVER_ERROR),
    INVALID_INPUT_DATA("SYS_1001", "Dữ liệu đầu vào không hợp lệ", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("SYS_1002", "Chưa đăng nhập hoặc token đã hết hạn", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED("SYS_1003", "Bạn không có quyền thực hiện thao tác này", HttpStatus.FORBIDDEN),
    SERVICE_UNAVAILABLE("SYS_1004", "Dịch vụ liên quan hiện không phản hồi, vui lòng thử lại sau", HttpStatus.SERVICE_UNAVAILABLE),
    OPTIMISTIC_LOCK_CONFLICT("SYS_1005", "Dữ liệu vừa được người khác cập nhật, vui lòng tải lại trang", HttpStatus.CONFLICT),
    RESOURCE_NOT_FOUND("SYS_1006", "Không tìm thấy tài nguyên được yêu cầu", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("SYS_1007", "Phương thức HTTP không được hỗ trợ cho đường dẫn này", HttpStatus.METHOD_NOT_ALLOWED),
    RESOURCE_BUSY("SYS_1008", "Hệ thống đang xử lý một yêu cầu khác trên cùng dữ liệu, vui lòng thử lại sau vài giây", HttpStatus.CONFLICT),

    // 2. Người dùng & Xác thực (AUTH: 1100 - 1199)
    USER_NOT_FOUND("AUTH_1101", "Không tìm thấy người dùng", HttpStatus.NOT_FOUND),
    PHONE_NUMBER_ALREADY_EXISTS("AUTH_1102", "Số điện thoại đã được đăng ký trong hệ thống", HttpStatus.CONFLICT),
    INVALID_CREDENTIALS("AUTH_1103", "Tên đăng nhập hoặc mật khẩu không chính xác", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_NOT_FOUND("AUTH_1104", "Không tìm thấy phiên Refresh Token", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_REVOKED("AUTH_1105", "Refresh Token đã bị thu hồi hoặc đã qua sử dụng (Cảnh báo bảo mật)", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_EXPIRED("AUTH_1106", "Refresh Token đã hết hạn, vui lòng đăng nhập lại", HttpStatus.UNAUTHORIZED),
    // v4.0: gộp ACCOUNT_LOCKED + ACCOUNT_INACTIVE (v3) thành một mã, trả 401 theo docs
    ACCOUNT_DISABLED("AUTH_1107", "Tài khoản đã bị khóa hoặc ngừng sử dụng, vui lòng liên hệ quản trị viên", HttpStatus.UNAUTHORIZED),
    VET_PROFILE_REQUIRED("AUTH_1108", "Tài khoản Bác sĩ phải có hồ sơ chuyên môn (số chứng chỉ hành nghề)", HttpStatus.BAD_REQUEST),
    USERNAME_ALREADY_EXISTS("AUTH_1109", "Tên đăng nhập đã tồn tại trong hệ thống", HttpStatus.CONFLICT),
    ROLE_NOT_FOUND("AUTH_1110", "Không tìm thấy vai trò", HttpStatus.NOT_FOUND),
    INVALID_CURRENT_PASSWORD("AUTH_1111", "Mật khẩu hiện tại không chính xác", HttpStatus.BAD_REQUEST),
    TOO_MANY_LOGIN_ATTEMPTS("AUTH_1112", "Đăng nhập sai quá nhiều lần, vui lòng thử lại sau ít phút", HttpStatus.TOO_MANY_REQUESTS),
    ROOM_NUMBER_ALREADY_ASSIGNED("AUTH_1113", "Số phòng này đã được gán cho nhân sự khác", HttpStatus.CONFLICT),
    CANNOT_MODIFY_OWN_ACCOUNT("AUTH_1114", "Không thể tự thay đổi trạng thái của chính tài khoản đang đăng nhập", HttpStatus.BAD_REQUEST),

    // 3. Thú cưng & Lịch hẹn (APPT: 1200 - 1299)
    PET_NOT_FOUND("APPT_1201", "Không tìm thấy hồ sơ thú cưng", HttpStatus.NOT_FOUND),
    APPOINTMENT_NOT_FOUND("APPT_1202", "Không tìm thấy lịch hẹn", HttpStatus.NOT_FOUND),
    SLOT_CAPACITY_EXCEEDED("APPT_1203", "Khung giờ khám đã kín chỗ, vui lòng chọn khung giờ khác", HttpStatus.BAD_REQUEST),
    CANNOT_CANCEL_APPOINTMENT("APPT_1204", "Chỉ được phép hủy hẹn trước giờ khám tối thiểu 2 tiếng", HttpStatus.BAD_REQUEST),
    // APPT_1205 theo Quy chuẩn v3.0 §5.1 (trước đây là SLOT_BUSY, đã dời sang APPT_1209 ngày 12/11/2026)
    APPOINTMENT_NOT_CHECKABLE("APPT_1205", "Lịch hẹn không ở trạng thái CONFIRMED hoặc không đúng ngày hẹn", HttpStatus.BAD_REQUEST),
    INVALID_APPOINTMENT_STATUS("APPT_1206", "Trạng thái lịch hẹn không hợp lệ để thực hiện thao tác này", HttpStatus.BAD_REQUEST),
    DUPLICATE_APPOINTMENT("APPT_1207", "Thú cưng này đã có lịch hẹn khác trong ngày", HttpStatus.CONFLICT),
    INVALID_APPOINTMENT_SLOT("APPT_1208", "Khung giờ đặt hẹn không hợp lệ", HttpStatus.BAD_REQUEST),
    SLOT_BUSY("APPT_1209", "Hệ thống đang xử lý một yêu cầu đặt lịch khác cho khung giờ này, vui lòng thử lại sau vài giây", HttpStatus.CONFLICT),

    // 4. Khám lâm sàng & Cận lâm sàng (CLIN: 1300 - 1399)
    VISIT_NOT_FOUND("CLIN_1301", "Không tìm thấy đợt khám y tế", HttpStatus.NOT_FOUND),
    DOCTOR_NOT_AVAILABLE("CLIN_1302", "Bác sĩ đang khám một ca khác, hãy kết thúc ca hiện tại trước", HttpStatus.CONFLICT),
    // CLIN_1303 đổi nghĩa ở v3.0: không còn "Khóa ưu tiên" mà là "hàng đợi trống" (khi Bác sĩ/KTV bấm Gọi số tiếp theo)
    QUEUE_EMPTY("CLIN_1303", "Hàng đợi hiện không còn bệnh nhân nào", HttpStatus.NOT_FOUND),
    PARACLINICAL_SERVICE_IN_PROGRESS("CLIN_1304", "Dịch vụ cận lâm sàng đang thực hiện, không thể hủy", HttpStatus.BAD_REQUEST),
    INVALID_STATUS_TRANSITION("CLIN_1305", "Trạng thái ca khám không hợp lệ để thực hiện thao tác này", HttpStatus.BAD_REQUEST),
    PET_HAS_OPEN_VISIT("CLIN_1306", "Thú cưng đang có đợt khám chưa kết thúc", HttpStatus.CONFLICT),
    DIAGNOSIS_REQUIRED("CLIN_1307", "Vui lòng nhập chẩn đoán trước khi chốt đợt khám", HttpStatus.BAD_REQUEST),
    VITAL_SIGNS_REQUIRED("CLIN_1308", "Vui lòng nhập cân nặng và thân nhiệt của thú cưng", HttpStatus.BAD_REQUEST),
    IN_ROOM_SERVICE_PENDING("CLIN_1309", "Còn dịch vụ tại phòng chưa thực hiện hoặc chưa hủy", HttpStatus.BAD_REQUEST),
    // CLIN_1310 đổi nghĩa 17/11/2026: dịch vụ đã thu vẫn hủy được, nhưng phí khám và dịch vụ khách tự hủy KHÔNG hoàn tiền
    REFUND_NOT_ALLOWED("CLIN_1310", "Phí khám và dịch vụ khách tự hủy không được hoàn tiền", HttpStatus.CONFLICT),
    NOT_ASSIGNED_DOCTOR("CLIN_1311", "Bạn không phải Bác sĩ được phân công cho ca khám này", HttpStatus.FORBIDDEN),
    // Phiếu xếp hàng (Quy chuẩn §5.1, thêm 17/11/2026)
    TICKET_NOT_FOUND("CLIN_1312", "Không tìm thấy phiếu xếp hàng", HttpStatus.NOT_FOUND),
    TICKET_NOT_WAITING("CLIN_1313", "Phiếu không còn ở trạng thái chờ gọi", HttpStatus.CONFLICT),
    NO_QUEUE_TICKET("CLIN_1314", "Ca chưa có phiếu xếp hàng (khách chưa nộp phí khám / tiền dịch vụ)", HttpStatus.BAD_REQUEST),
    // v4.0 (CLIN_1315-1318): SERVICE_PRICE_NOT_FOUND, MEDICAL_RECORD_LOCKED, QUEUE_UNAVAILABLE chưa có nơi dùng cho tới các Phase sau
    SERVICE_PRICE_NOT_FOUND("CLIN_1315", "Dịch vụ chưa có giá đang áp dụng", HttpStatus.CONFLICT),
    // v4.0: gộp INVALID_DOCTOR (v3, 400) vào DOCTOR_INACTIVE (409 theo docs)
    DOCTOR_INACTIVE("CLIN_1316", "Bác sĩ được chọn đã bị khóa hoặc ngừng làm việc", HttpStatus.CONFLICT),
    MEDICAL_RECORD_LOCKED("CLIN_1317", "Hồ sơ khám đã khóa (đợt khám đã chốt), không thể chỉnh sửa", HttpStatus.CONFLICT),
    QUEUE_UNAVAILABLE("CLIN_1318", "Hàng đợi tạm thời không truy cập được, vui lòng thử lại sau ít giây", HttpStatus.SERVICE_UNAVAILABLE),
    LAB_ITEM_NOT_IN_SERVICE("CLIN_1319", "Chỉ số này không thuộc dịch vụ xét nghiệm được chỉ định", HttpStatus.BAD_REQUEST),
    // Mã chỉ có trong code (không có trong Quy chuẩn §5.1): dời xuống cuối nhóm ngày 12/11/2026 để nhường số 1303-1311 cho docs
    SERVICE_NOT_FOUND("CLIN_1320", "Không tìm thấy dịch vụ kỹ thuật hoặc dịch vụ đã ngừng sử dụng", HttpStatus.NOT_FOUND),
    SERVICE_DETAIL_NOT_FOUND("CLIN_1321", "Không tìm thấy chỉ định dịch vụ", HttpStatus.NOT_FOUND),
    INVALID_SERVICE_DETAIL_STATUS("CLIN_1322", "Trạng thái chỉ định dịch vụ không hợp lệ để thực hiện thao tác này", HttpStatus.BAD_REQUEST),
    // Dời số ngày 17/11/2026 để nhường CLIN_1313 / CLIN_1314 cho phiếu xếp hàng (mã chỉ có trong code)
    LAB_REFERENCE_NOT_FOUND("CLIN_1323", "Chưa cấu hình khoảng tham chiếu của chỉ số này cho loài của thú cưng", HttpStatus.BAD_REQUEST),
    INVALID_IMAGING_FILE("CLIN_1324", "Tệp ảnh không hợp lệ (chỉ nhận JPEG, PNG hoặc DICOM, tối đa 20MB)", HttpStatus.BAD_REQUEST),
    // Dời số ngày 20/11/2026 (D3) để nhường CLIN_1315-1318 cho docs v4.0 (mã chỉ có trong code)
    SPECIES_NOT_FOUND("CLIN_1325", "Không tìm thấy loài thú cưng", HttpStatus.NOT_FOUND),
    LAB_ITEM_NOT_FOUND("CLIN_1326", "Không tìm thấy chỉ số xét nghiệm", HttpStatus.NOT_FOUND),
    CODE_ALREADY_EXISTS("CLIN_1327", "Mã đã tồn tại trong danh mục", HttpStatus.CONFLICT),

    // 5. Hóa đơn & Kho dược (BILL/PHARM: 1400 - 1499) – dùng cho cả service_invoices (clinical) và medicine_invoices (billing-pharmacy)
    INVOICE_NOT_FOUND("BILL_1401", "Không tìm thấy hóa đơn", HttpStatus.NOT_FOUND),
    DISCOUNT_EXCEEDS_INVOICE_AMOUNT("BILL_1402", "Số tiền giảm giá không được vượt quá số tiền của hóa đơn này", HttpStatus.BAD_REQUEST),
    INVOICE_ALREADY_PAID("BILL_1403", "Hóa đơn này đã được thanh toán trước đó", HttpStatus.CONFLICT),
    PRESCRIPTION_NOT_FOUND("PHARM_1404", "Không tìm thấy đơn thuốc của đợt khám", HttpStatus.NOT_FOUND),
    MEDICINE_NOT_FOUND("PHARM_1405", "Không tìm thấy thuốc trong danh mục kho", HttpStatus.NOT_FOUND),
    INSUFFICIENT_STOCK("PHARM_1406", "Số lượng tồn kho không đủ để bán", HttpStatus.CONFLICT),
    PRESCRIPTION_ALREADY_INVOICED("PHARM_1407", "Đơn thuốc này đã được lập hóa đơn thuốc trước đó", HttpStatus.CONFLICT),
    INVOICE_ALREADY_CANCELLED("BILL_1408", "Hóa đơn này đã bị hủy", HttpStatus.CONFLICT),
    VISIT_NOT_READY_FOR_PAYMENT("BILL_1409", "Dịch vụ làm tại phòng chỉ thu khi Bác sĩ đã chốt chuyên môn", HttpStatus.BAD_REQUEST),
    // v4.0: chưa có nơi dùng cho tới bước đặt giá theo thời điểm hiệu lực (PriceBook)
    PRICE_EFFECTIVE_DATE_INVALID("BILL_1410", "Thời điểm hiệu lực của giá mới phải sau thời điểm bắt đầu của giá đang áp dụng", HttpStatus.BAD_REQUEST),
    MEDICINE_PRICE_NOT_FOUND("PHARM_1412", "Thuốc chưa có giá bán đang áp dụng", HttpStatus.CONFLICT),
    PRESCRIPTION_LOCKED("PHARM_1411", "Đơn thuốc đã khóa (đợt khám đã chốt hoặc đơn đã được bán), không thể chỉnh sửa", HttpStatus.CONFLICT),
    // Mã chỉ có trong code (không có trong Quy chuẩn §5.1); dời từ PHARM_1412 ngày 20/11/2026 (D3) để nhường số cho docs v4.0
    INVALID_STOCK_ADJUSTMENT("PHARM_1415", "Điều chỉnh làm tồn kho âm, không thể thực hiện", HttpStatus.BAD_REQUEST),
    // Dời số ngày 12/11/2026 để nhường PHARM_1408/1409/1410/1411 cho docs
    PRESCRIPTION_ALREADY_EXISTS("PHARM_1413", "Đợt khám này đã có đơn thuốc, hãy cập nhật đơn hiện có", HttpStatus.CONFLICT),
    MEDICINE_CODE_ALREADY_EXISTS("PHARM_1414", "Mã thuốc đã tồn tại trong danh mục", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public static ErrorCode fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ErrorCode ec : values()) {
            if (ec.code.equals(code)) {
                return ec;
            }
        }
        return null;
    }
}
