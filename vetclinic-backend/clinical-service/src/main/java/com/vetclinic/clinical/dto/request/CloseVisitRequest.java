package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Chốt đợt khám (T05 / T09): ghi vào medical_records. {@code finalDiagnosis} bắt buộc (EX-12) nhưng kiểm tra ở service để trả mã
 * CLIN_1307 riêng; ca không làm cận lâm sàng được dùng chẩn đoán sơ bộ đã ghi trên hồ sơ khám.
 */
public record CloseVisitRequest(
        String finalDiagnosis,
        @Size(max = 4000, message = "Phác đồ điều trị tối đa 4000 ký tự") String treatmentPlan,
        @Size(max = 2000, message = "Lời dặn tối đa 2000 ký tự") String doctorAdvice,
        LocalDate followUpDate
) {
}
