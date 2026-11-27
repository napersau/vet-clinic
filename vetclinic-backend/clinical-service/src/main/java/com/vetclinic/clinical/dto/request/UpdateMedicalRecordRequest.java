package com.vetclinic.clinical.dto.request;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Bác sĩ sửa hồ sơ khám (PUT). Trường nào null thì giữ nguyên; chuỗi rỗng = xóa nội dung. {@code version} (tùy chọn): phiên bản
 * hồ sơ mà màn hình đang xem; lệch với bản đang lưu (đã có người sửa trước) thì trả 409 SYS_1005 thay vì ghi đè.
 */
public record UpdateMedicalRecordRequest(
        @Size(max = 4000, message = "Bệnh sử tối đa 4000 ký tự") String anamnesis,
        @Size(max = 4000, message = "Kết quả khám lâm sàng tối đa 4000 ký tự") String clinicalFindings,
        @Size(max = 2000, message = "Chẩn đoán sơ bộ tối đa 2000 ký tự") String preliminaryDiagnosis,
        @Size(max = 2000, message = "Chẩn đoán xác định tối đa 2000 ký tự") String finalDiagnosis,
        @Size(max = 4000, message = "Phác đồ điều trị tối đa 4000 ký tự") String treatmentPlan,
        @Size(max = 2000, message = "Lời dặn tối đa 2000 ký tự") String doctorAdvice,
        LocalDate followUpDate,
        Long version
) {
}
