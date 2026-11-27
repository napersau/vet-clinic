package com.vetclinic.reporting.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** {@code GET /reports/doctors/performance}: hiệu suất từng Bác sĩ trong khoảng ngày. Tên lấy từ bảng phẳng của reporting (không gọi auth-service). */
public record DoctorPerformanceResponse(
        LocalDate fromDate,
        LocalDate toDate,
        List<Doctor> doctors
) {

    public record Doctor(
            UUID doctorId,
            String doctorName,
            long completedVisits,
            long cancelledVisits,
            long emergencyVisits,
            BigDecimal serviceNetRevenue
    ) {
    }
}
