package com.vetclinic.clinical.dto.response;

import com.vetclinic.clinical.constant.DoctorStatus;

import java.util.UUID;
import java.util.List;

/**
 * Hàng đợi riêng của một Bác sĩ trên bảng điều phối: ca WAITING và RESULTS_READY có doctor_id của Bác sĩ, đã xếp theo thứ tự gọi.
 * waitingCount = số ca đang chờ (chưa vào phòng). Ca đang IN_PROGRESS không nằm trong hàng đợi nhưng làm status = BUSY.
 */
public record DoctorQueueResponse(
        UUID doctorId,
        String fullName,
        String roomNumber,
        DoctorStatus status,
        int waitingCount,
        List<VisitResponse> visits
) {
}
