package com.vetclinic.clinical.dto.response;

import java.util.UUID;
import com.vetclinic.clinical.constant.VisitStatus;

/**
 * Cho billing-pharmacy-service: kiểm tra trạng thái / bác sĩ / chủ nuôi khi kê đơn, và làm giàu danh sách hóa đơn
 * bằng mã ca khám, tên thú cưng.
 */
public record VisitSummaryResponse(
        UUID id,
        String visitCode,
        VisitStatus status,
        UUID petId,
        String petName,
        UUID ownerId,
        UUID doctorId
) {
}
