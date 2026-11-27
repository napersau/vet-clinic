package com.vetclinic.reporting.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code GET /reports/visits/summary} (FR-70): chỉ số vận hành theo ngày tiếp đón (giờ phòng khám) từ {@code visit_facts}.
 * {@code paraclinicalVisits} = ca còn doanh thu ròng dương ở nhóm xét nghiệm / chẩn đoán hình ảnh (đã thu và chưa hoàn hết); {@code routineVisits} = phần còn lại.
 * {@code byStatus} chỉ có WAITING (chưa hoàn tất), COMPLETED, CANCELLED.
 */
public record VisitSummaryResponse(
        LocalDate fromDate,
        LocalDate toDate,
        long totalVisits,
        long completedVisits,
        long cancelledVisits,
        long emergencyVisits,
        long routineVisits,
        long paraclinicalVisits,
        Map<String, Long> byStatus,
        List<DoctorLoad> doctors
) {

    /** Số ca một Bác sĩ phụ trách trong kỳ (theo Bác sĩ ghi nhận trên ca) và số ca đã hoàn tất. */
    public record DoctorLoad(UUID doctorId, String doctorName, long assignedVisits, long completedVisits) {
    }
}
