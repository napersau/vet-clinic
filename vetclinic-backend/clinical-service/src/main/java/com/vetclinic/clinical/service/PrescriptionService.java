package com.vetclinic.clinical.service;

import com.vetclinic.clinical.dto.request.PrescriptionRequest;
import com.vetclinic.clinical.dto.response.PrescriptionResponse;
import com.vetclinic.clinical.dto.response.PrescriptionSummary;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Đơn thuốc điện tử (UC-31, UC-39) thuộc ngữ cảnh lâm sàng: nằm cùng CSDL với đợt khám, kê / sửa cùng quy tắc khóa hồ sơ. */
public interface PrescriptionService {

    /**
     * Lưu cả đơn (tạo lần đầu hoặc thay nội dung): chỉ Bác sĩ phụ trách (CLIN_1311) khi đợt khám IN_PROGRESS (PHARM_1411 nếu đã khóa); thuốc phải
     * đang kinh doanh (PHARM_1405); đơn đã có hóa đơn thuốc PAID thì khóa (PHARM_1411). Kê vượt tồn kho và trùng dị ứng chỉ cảnh báo.
     */
    PrescriptionResponse save(UUID visitId, PrescriptionRequest request);

    /** Đơn của đợt khám (PHARM_1404 nếu chưa kê). Chủ nuôi chỉ xem được đơn của thú cưng mình. */
    PrescriptionResponse getByVisit(UUID visitId);

    /** In đơn cho khách mua ngoài (BR-32): chỉ đọc, không tạo hóa đơn, không đổi gì trong hệ thống. */
    PrescriptionResponse print(UUID visitId);

    /** Bệnh án trọn đời: đơn thuốc của nhiều đợt khám, khóa theo id đợt khám. */
    Map<UUID, PrescriptionSummary> summariesByVisit(Collection<UUID> visitIds);
}
