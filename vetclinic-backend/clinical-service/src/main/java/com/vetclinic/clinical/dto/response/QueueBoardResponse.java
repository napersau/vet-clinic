package com.vetclinic.clinical.dto.response;

import java.util.UUID;
import java.util.List;

/**
 * Bảng điều phối của Lễ tân (UC-20, FR-21): danh sách chờ phân công (phiếu DOCTOR đang chờ chưa có Bác sĩ) và một hàng đợi cho mỗi Bác sĩ
 * ACTIVE. suggestedDoctorId = Bác sĩ có ít người chờ nhất (chỉ gợi ý, Lễ tân quyết định; được xếp cho BẤT KỲ Bác sĩ nào).
 */
public record QueueBoardResponse(
        List<VisitResponse> unassigned,
        List<DoctorQueueResponse> doctors,
        UUID suggestedDoctorId,
        List<VisitResponse> awaitingPayment   // ca vừa tiếp đón, chưa nộp phí khám nên chưa có phiếu (chưa hiện ở hàng đợi Bác sĩ)
) {
}
